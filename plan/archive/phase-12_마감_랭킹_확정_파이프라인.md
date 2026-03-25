# Phase 12: 마감 시점 랭킹 확정 파이프라인

> 셀링포인트 2 핵심: "Eventual Consistency → 특정 시점 Strong Consistency 전환"
> 의존: Phase 11 (FINALIZING 상태 + 이벤트 시스템)

---

## 1. 현재 문제 분석

### 1-1. 마감 시점 정합성 갭

현재 투표 흐름과 마감 시점의 문제를 코드 레벨에서 추적한다.

```
[현재 투표 흐름 — VoteService.voteAsync()]

① Entry 조회 (DB)
② 중복 체크 (DB)
③ Redis ZINCRBY + ZSCORE (Pipeline, 즉시 반영)
④ SQS Fire & Forget (비동기, 300ms 후 DB 저장)
```

```
[문제 시나리오]

23:59:58  투표 A → Redis 반영 (즉시), SQS 전송 (비동기)
23:59:59  투표 B → Redis 반영 (즉시), SQS 전송 (비동기)
00:00:00  챌린지 마감!
00:00:01  SQS Consumer가 투표 A 처리 중 (DB 저장)
00:00:05  SQS Consumer가 투표 B 아직 큐에 대기 중

→ Redis: 투표 A, B 모두 반영 (정확)
→ DB:    투표 A만 반영, B는 미반영 (불일치)
→ 5분 후 스케줄러가 Redis를 DB 기준으로 덮어씀
→ 투표 B가 Redis에서도 사라짐 → 랭킹 변동!
```

### 1-2. 현재 VoteConsistencyScheduler의 한계

```java
// 현재: VoteConsistencyScheduler.java (Line 58-82)
if (redisCount > dbCount) {
    Thread.sleep(30000);  // 30초 대기 후
    // DB 기준으로 Redis 덮어씀
    rankingRedisService.initEntry(challengeId, entry.getId(), dbCountRetry);
}
```

**문제**: "30초 대기" 로 SQS 처리를 기다리지만, 마감 시점에는 이것만으로 부족하다. SQS visibility timeout(30초) + Consumer 처리 시간까지 고려하면 최대 1분까지 미처리 메시지가 남을 수 있다.

### 1-3. 랭킹 스냅샷이 없다

마감 후에도 투표 취소(`cancelVote`)나 데이터 수정이 가능하면 확정된 랭킹이 변할 수 있다. 현재는 immutable한 랭킹 기록이 없다.

---

## 2. 목표 수치

| 항목 | Before | After | 측정 방법 |
|------|--------|-------|----------|
| 마감 시점 Redis↔DB 정합성 | Eventual (5분 지연) | Strong (마감 후 60초 이내) | k6 시나리오 + DB/Redis 비교 |
| 미처리 SQS 메시지 | 유실 가능 | 0건 (드레인 보장) | SQS CloudWatch 메트릭 |
| 랭킹 스냅샷 | 없음 | immutable 저장 | ranking_snapshot 테이블 확인 |
| 마감 후 랭킹 변동 | 가능 | 불가 | 스냅샷 vs 실시간 랭킹 비교 |

---

## 3. 구현 계획

### 3-1. RankingSnapshot 엔티티

```java
// 신규: entity/RankingSnapshot.java

@Entity
@Table(name = "ranking_snapshot",
       indexes = @Index(name = "idx_snapshot_challenge",
                        columnList = "challenge_id, final_rank"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RankingSnapshot extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "challenge_id", nullable = false)
    private Long challengeId;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "member_id", nullable = false)
    private Integer memberId;

    @Column(name = "final_rank", nullable = false)
    private Integer finalRank;

    @Column(name = "final_vote_count", nullable = false)
    private Integer finalVoteCount;

    @Column(name = "finalized_at", nullable = false)
    private LocalDateTime finalizedAt;
}
```

```sql
-- DDL
CREATE TABLE ranking_snapshot (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    challenge_id      BIGINT       NOT NULL,
    entry_id          BIGINT       NOT NULL,
    pet_id            BIGINT       NOT NULL,
    member_id         INT          NOT NULL,
    final_rank        INT          NOT NULL,
    final_vote_count  INT          NOT NULL,
    finalized_at      DATETIME(6)  NOT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_snapshot_challenge (challenge_id, final_rank),
    UNIQUE KEY uk_snapshot_entry (challenge_id, entry_id)
);
```

### 3-2. RankingSnapshotRepository

```java
// 신규: repository/RankingSnapshotRepository.java

public interface RankingSnapshotRepository extends JpaRepository<RankingSnapshot, Long> {

    List<RankingSnapshot> findByChallengeIdOrderByFinalRankAsc(Long challengeId);

    boolean existsByChallengeId(Long challengeId);
}
```

### 3-3. SQS 드레인 서비스

```java
// 신규: service/SqsDrainService.java

@Slf4j
@Component
@RequiredArgsConstructor
public class SqsDrainService {

    private final SqsAsyncClient sqsAsyncClient;

    @Value("${app.sqs.vote-queue-url:}")
    private String queueUrl;

    /**
     * SQS 큐가 비워질 때까지 대기
     *
     * ApproximateNumberOfMessages + ApproximateNumberOfMessagesNotVisible 체크
     * - Messages: 큐에 대기 중인 메시지
     * - NotVisible: Consumer가 처리 중인 메시지 (visibility timeout)
     *
     * @param maxWait 최대 대기 시간
     * @return 드레인 완료 여부
     */
    public boolean waitUntilDrained(Duration maxWait) {
        if (queueUrl.isEmpty()) {
            log.warn("[SqsDrain] Queue URL not configured, skipping drain");
            return true;
        }

        Instant deadline = Instant.now().plus(maxWait);
        int pollCount = 0;

        while (Instant.now().isBefore(deadline)) {
            int totalMessages = getApproximateMessageCount();
            pollCount++;

            log.info("[SqsDrain] Poll #{}: ~{} messages remaining", pollCount, totalMessages);

            if (totalMessages == 0) {
                log.info("[SqsDrain] Queue drained after {} polls", pollCount);
                return true;
            }

            try {
                Thread.sleep(2000); // 2초 간격 폴링
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        int remaining = getApproximateMessageCount();
        log.warn("[SqsDrain] Timeout after {}s, ~{} messages still in queue",
                 maxWait.getSeconds(), remaining);
        return remaining == 0;
    }

    private int getApproximateMessageCount() {
        // SQS GetQueueAttributes API
        // ApproximateNumberOfMessages + ApproximateNumberOfMessagesNotVisible
        var request = GetQueueAttributesRequest.builder()
            .queueUrl(queueUrl)
            .attributeNames(
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
            .build();

        var response = sqsAsyncClient.getQueueAttributes(request).join();
        var attrs = response.attributes();

        int visible = Integer.parseInt(
            attrs.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
        int notVisible = Integer.parseInt(
            attrs.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0"));

        return visible + notVisible;
    }
}
```

**주의**: `ApproximateNumberOfMessages`는 정확한 값이 아닌 근사치. 0이 되어도 1~2개 남아있을 수 있으므로, 드레인 후 정합성 검증을 반드시 수행한다.

### 3-4. 랭킹 확정 서비스 (핵심)

```java
// 신규: service/RankingFinalizationService.java

@Slf4j
@Service
@RequiredArgsConstructor
public class RankingFinalizationService {

    private final ChallengeRepository challengeRepository;
    private final EntryRepository entryRepository;
    private final RankingSnapshotRepository snapshotRepository;
    private final RankingRedisService rankingRedisService;
    private final SqsDrainService sqsDrainService;

    /**
     * 마감 랭킹 확정 파이프라인
     *
     * Phase 11의 ChallengeEventListener에서 FINALIZING 이벤트 시 호출
     *
     * 흐름:
     * ① 이미 스냅샷이 있으면 스킵 (멱등성)
     * ② SQS 큐 드레인 대기 (최대 60초)
     * ③ Redis↔DB 최종 정합성 검증 + 보정
     * ④ DB 기준 랭킹 스냅샷 생성
     * ⑤ 챌린지 상태를 ENDED로 전환
     */
    @Transactional
    public void finalize(Long challengeId) {
        log.info("[Finalize] Starting for challengeId={}", challengeId);

        // ① 멱등성: 이미 스냅샷 존재 시 스킵
        if (snapshotRepository.existsByChallengeId(challengeId)) {
            log.info("[Finalize] Snapshot already exists, skipping: challengeId={}", challengeId);
            completeFinalization(challengeId);
            return;
        }

        // ② SQS 큐 드레인 (최대 60초 대기)
        boolean drained = sqsDrainService.waitUntilDrained(Duration.ofSeconds(60));
        if (!drained) {
            log.warn("[Finalize] SQS drain timeout, proceeding with DB as source of truth");
        }

        // ③ Redis↔DB 최종 정합성 검증
        verifyAndSyncConsistency(challengeId);

        // ④ 랭킹 스냅샷 생성 (DB 기준, Source of Truth)
        createSnapshot(challengeId);

        // ⑤ 챌린지 상태 전환: FINALIZING → ENDED
        completeFinalization(challengeId);

        log.info("[Finalize] Completed for challengeId={}", challengeId);
    }

    /**
     * ③ Redis↔DB 정합성 검증 + 보정
     *
     * 기존 VoteConsistencyScheduler와 유사하지만:
     * - 30초 대기 없음 (이미 SQS 드레인 완료)
     * - 불일치 시 즉시 DB 기준 보정
     * - 결과를 로그에 상세 기록 (포트폴리오용)
     */
    private void verifyAndSyncConsistency(Long challengeId) {
        List<Entry> entries = entryRepository.findByChallengeId(challengeId);
        int mismatchCount = 0;

        for (Entry entry : entries) {
            int dbCount = entryRepository.getVoteCount(entry.getId());
            int redisCount = rankingRedisService.getVoteCount(challengeId, entry.getId());

            if (dbCount != redisCount) {
                mismatchCount++;
                log.warn("[Finalize] Mismatch: entryId={}, db={}, redis={} → syncing to db",
                         entry.getId(), dbCount, redisCount);
                rankingRedisService.initEntry(challengeId, entry.getId(), dbCount);
            }
        }

        log.info("[Finalize] Consistency check: {} entries, {} mismatches fixed",
                 entries.size(), mismatchCount);
    }

    /**
     * ④ DB 기준 랭킹 스냅샷 생성
     *
     * voteCount DESC 정렬 → 순위 부여 → immutable 저장
     */
    private void createSnapshot(Long challengeId) {
        List<Entry> entries = entryRepository
            .findByChallengeIdOrderByVoteCountDesc(challengeId);

        LocalDateTime now = LocalDateTime.now();
        List<RankingSnapshot> snapshots = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            snapshots.add(RankingSnapshot.builder()
                .challengeId(challengeId)
                .entryId(entry.getId())
                .petId(entry.getPet().getId())
                .memberId(entry.getMember().getId())
                .finalRank(i + 1)
                .finalVoteCount(entry.getVoteCount())
                .finalizedAt(now)
                .build());
        }

        snapshotRepository.saveAll(snapshots);
        log.info("[Finalize] Snapshot created: {} entries ranked", snapshots.size());
    }

    private void completeFinalization(Long challengeId) {
        Challenge challenge = challengeRepository.findById(challengeId)
            .orElseThrow(() -> new IllegalStateException("Challenge not found: " + challengeId));

        if (challenge.getStatus() == ChallengeStatus.FINALIZING) {
            challenge.transitionTo(ChallengeStatus.ENDED);
            challengeRepository.save(challenge);
        }
    }
}
```

### 3-5. 마감 후 투표 취소 차단

```java
// 변경: service/VoteService.java — cancelVote()

@Transactional
public VoteResponseDto.VoteResult cancelVote(Long entryId, Integer memberId) {
    Vote vote = voteRepository.findByEntryIdAndMemberId(entryId, memberId)
        .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

    Challenge challenge = vote.getEntry().getChallenge();

    // 마감 후 투표 취소 차단 (FINALIZING, ENDED, RESULT_ANNOUNCED)
    if (!challenge.isVotable()) {
        throw new GeneralException(ErrorStatus._BAD_REQUEST);
    }

    // ... 기존 취소 로직
}
```

### 3-6. 확정 랭킹 조회 API

```java
// 변경: controller/ChallengeController.java — 엔드포인트 추가

@GetMapping("/{challengeId}/result")
public ApiResponse<List<RankingSnapshotResponse>> getFinalRanking(
        @PathVariable Long challengeId) {
    return ApiResponse.onSuccess(
        challengeService.getFinalRanking(challengeId));
}
```

```java
// 변경: service/ChallengeService.java — 메서드 추가

public List<RankingSnapshotResponse> getFinalRanking(Long challengeId) {
    Challenge challenge = challengeRepository.findById(challengeId)
        .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

    // ENDED 또는 RESULT_ANNOUNCED만 확정 랭킹 조회 가능
    if (challenge.getStatus() != ENDED && challenge.getStatus() != RESULT_ANNOUNCED) {
        throw new GeneralException(ErrorStatus._BAD_REQUEST);
    }

    List<RankingSnapshot> snapshots = snapshotRepository
        .findByChallengeIdOrderByFinalRankAsc(challengeId);

    return snapshots.stream()
        .map(RankingSnapshotResponse::from)
        .toList();
}
```

---

## 4. 수정 대상 파일 목록

| 파일 | 작업 |
|------|------|
| `service/VoteService.java` | `cancelVote()` 마감 후 차단 추가 |
| `controller/ChallengeController.java` | `/result` 엔드포인트 추가 |
| `service/ChallengeService.java` | `getFinalRanking()` 추가 |
| **신규** `entity/RankingSnapshot.java` | 스냅샷 엔티티 |
| **신규** `repository/RankingSnapshotRepository.java` | 스냅샷 레포지토리 |
| **신규** `service/RankingFinalizationService.java` | 확정 파이프라인 핵심 |
| **신규** `service/SqsDrainService.java` | SQS 드레인 |
| **신규** `dto/response/RankingSnapshotResponse.java` | 응답 DTO |
| **DB** `ranking_snapshot` 테이블 | DDL 실행 |

---

## 5. 검증 계획

### 5-1. 핵심 시나리오: 마감 시점 정합성 100% 검증

```
[k6 부하 테스트 시나리오]

Setup:
  - 챌린지 1개: endAt = now + 3분
  - Entry 10개, 각 Entry 초기 투표 0

Phase 1 (0~2분50초): 투표 트래픽
  - 50~200 VUs가 랜덤 Entry에 투표
  - 투표 수 누적 확인

Phase 2 (2분50초~3분): 마감 직전 폭주
  - VUs 200으로 유지
  - 마지막 투표 시각 기록

Phase 3 (3분): 마감 처리
  - FINALIZING 자동 전환 확인
  - 투표 API 400 응답 확인
  - SQS 드레인 로그 확인

Phase 4 (3분~4분): 정합성 검증
  - ENDED 전환 확인
  - ranking_snapshot 테이블 조회
  - 각 Entry: DB voteCount == snapshot.finalVoteCount 확인
  - 각 Entry: Redis score == DB voteCount 확인
  - 불일치 건수: 0 확인
```

### 5-2. 단위 테스트

```java
@Test void 멱등성_이미_스냅샷_있으면_재생성하지_않는다() {
    // given: 이미 스냅샷 존재
    given(snapshotRepository.existsByChallengeId(1L)).willReturn(true);

    // when
    rankingFinalizationService.finalize(1L);

    // then: createSnapshot 호출되지 않음
    verify(snapshotRepository, never()).saveAll(any());
}

@Test void 정합성_불일치_시_DB_기준_보정() {
    // given: Redis 10, DB 8 (SQS 지연)
    given(rankingRedisService.getVoteCount(1L, 100L)).willReturn(10);
    given(entryRepository.getVoteCount(100L)).willReturn(8);

    // when
    rankingFinalizationService.finalize(1L);

    // then: Redis를 DB 기준(8)으로 보정
    verify(rankingRedisService).initEntry(1L, 100L, 8);
}
```

### 5-3. Grafana 모니터링 항목

```
- SQS ApproximateNumberOfMessages (드레인 과정 시각화)
- 마감 처리 소요 시간 (FINALIZING → ENDED 간격)
- 정합성 불일치 건수 (mismatch count)
- 스냅샷 생성 건수
```

---

## 6. 파이프라인 전체 흐름도

```
[Phase 11 스케줄러]
  endAt 도래 감지 → CLOSING_SOON → FINALIZING 전환
                                        |
                                   이벤트 발행
                                        |
                              ChallengeEventListener
                                        |
                                        v
[Phase 12 파이프라인 — RankingFinalizationService.finalize()]
  |
  ├─ ① 멱등성 체크 (스냅샷 존재?)
  |     └─ YES → completeFinalization() → 끝
  |     └─ NO  → 계속
  |
  ├─ ② SQS 드레인 대기 (최대 60초)
  |     └─ 2초마다 큐 메시지 수 폴링
  |     └─ 0이면 → 계속
  |     └─ 타임아웃 → 경고 로그, DB 기준 진행
  |
  ├─ ③ Redis↔DB 정합성 검증
  |     └─ Entry별 Redis score vs DB voteCount
  |     └─ 불일치 → DB 기준으로 Redis 보정
  |
  ├─ ④ 랭킹 스냅샷 생성
  |     └─ DB voteCount DESC 정렬 → 순위 부여
  |     └─ ranking_snapshot 테이블에 INSERT
  |
  └─ ⑤ FINALIZING → ENDED 전환
        └─ 이후 투표/취소 모두 차단
```

---

## 7. 포트폴리오 서술 포인트

이 Phase를 구현하면 다음과 같이 서술할 수 있다:

> **"비동기 아키텍처(Redis + SQS)에서 평소에는 Eventual Consistency로 빠른 응답을 유지하면서, 콘테스트 마감이라는 비즈니스 크리티컬 시점에만 Strong Consistency로 전환하는 파이프라인을 설계했습니다."**
>
> 구체적으로:
> - SQS 큐 드레인으로 미처리 메시지 0건 보장
> - Redis↔DB 정합성 검증 후 DB(Source of Truth) 기준 보정
> - Immutable 랭킹 스냅샷으로 확정 결과 보존
> - 멱등 설계로 서버 재시작 시에도 안전한 재실행 보장
>
> 검증 결과: 마감 시점 50 VUs 동시 투표 환경에서 **Redis↔DB 정합성 100%** 달성