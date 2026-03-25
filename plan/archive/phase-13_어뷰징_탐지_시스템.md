# Phase 13: 투표 어뷰징 탐지 시스템

> 셀링포인트 3: "도메인 문제를 기술로 해결 — 실시간 투표 스트림에서 어뷰징 패턴 탐지"

---

## 1. 현재 문제 분석

### 1-1. 현재 방어 수단

```java
// 현재 유일한 방어: DB UNIQUE 제약
// entity/Vote.java
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"entry_id", "member_id"}))

// VoteService.voteAsync() Line 135
if (voteRepository.existsByEntryIdAndMemberId(entryId, memberId)) {
    throw new GeneralException(ErrorStatus._BAD_REQUEST);
}
```

**한계**: "같은 계정이 같은 Entry에 중복 투표"만 막을 수 있다.

### 1-2. 못 막는 어뷰징 패턴

```
패턴 1 — 다중 계정 (Multi-Account)
  같은 사람이 계정 A, B, C로 같은 Entry에 투표
  → 각각 다른 member_id이므로 UNIQUE 통과

패턴 2 — 조직 투표 (Vote Manipulation)
  SNS에 "이 사진에 투표해주세요" 공유 → 30분 내 100표 집중
  → 정상 투표 패턴(일 평균 5표)과 비정상적 격차

패턴 3 — 봇 투표 (Automated Voting)
  스크립트로 3초 간격 균일 투표
  → 사람의 자연스러운 행동 패턴이 아님 (표준편차 극히 낮음)
```

### 1-3. 현재 투표 API에 메타데이터가 없다

```java
// 현재: EntryController.java
@PostMapping("/{entryId}/votes")
public ApiResponse<VoteResponseDto.VoteResult> vote(
    @PathVariable Long entryId,
    @LoginUser Integer memberId) {
    // clientIp, userAgent 등 메타데이터 수집 안 함
}
```

---

## 2. 목표 수치

| 항목 | Before | After | 측정 방법 |
|------|--------|-------|----------|
| 다중 계정 감지 | 불가 | 같은 IP 3+ 계정 탐지 | 테스트 시나리오 |
| 봇 투표 감지 | 불가 | 균일 간격 패턴 탐지 (표준편차 < 500ms) | 테스트 시나리오 |
| Rate Limiting | 없음 | IP당 10회/분, 회원당 5회/분 | k6 부하 테스트 |
| 정상 투표 오탐율 | N/A | 0% (정상 트래픽에서 차단 0건) | 혼합 부하 테스트 |
| 투표 API 오버헤드 | N/A | +5ms 이내 (Redis O(1) 연산) | k6 Before/After |

---

## 3. 구현 계획

### Phase 13-1: Redis Sliding Window Rate Limiting

**목표**: 과도한 투표 속도를 실시간 차단 (1차 방어선)

#### 3-1-1. Rate Limiter 서비스

```java
// 신규: service/RateLimiterService.java

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String RATE_KEY_PREFIX = "rate:";

    /**
     * Sliding Window Counter (Redis Sorted Set)
     *
     * 원리:
     * - Sorted Set의 score = 타임스탬프
     * - ZADD로 이벤트 기록 → ZREMRANGEBYSCORE로 윈도우 밖 삭제 → ZCARD로 카운트
     * - Pipeline으로 3 명령 1 RTT
     *
     * 장점: Fixed Window의 경계 문제 없음 (윈도우가 슬라이딩)
     * 비용: Redis Sorted Set O(log N) per operation
     *
     * @param key   rate limit 대상 (예: "ip:1.2.3.4", "member:123")
     * @param limit 윈도우 내 허용 횟수
     * @param window 윈도우 크기
     * @return true if rate limited (차단), false if allowed (허용)
     */
    public boolean isRateLimited(String key, int limit, Duration window) {
        String redisKey = RATE_KEY_PREFIX + key;
        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();

        List<Object> results = redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                // 윈도우 밖 이벤트 삭제
                operations.opsForZSet().removeRangeByScore(redisKey, 0, windowStart);
                // 현재 이벤트 추가 (member = unique ID, score = timestamp)
                operations.opsForZSet().add(redisKey, now + ":" + Math.random(), now);
                // 윈도우 내 이벤트 수
                operations.opsForZSet().zCard(redisKey);
                // TTL 설정 (윈도우 크기 + 여유)
                operations.expire(redisKey, window.plusSeconds(10));
                return null;
            }
        });

        Long count = (Long) results.get(2);
        boolean limited = count != null && count > limit;

        if (limited) {
            log.warn("[RateLimit] Blocked: key={}, count={}, limit={}", key, count, limit);
        }

        return limited;
    }
}
```

#### 3-1-2. VoteService에 Rate Limiting 적용

```java
// 변경: service/VoteService.java — voteAsync() 메서드에 추가

// 생성자에 주입
private final RateLimiterService rateLimiterService;

public VoteResponseDto.VoteResult voteAsync(Long entryId, Integer memberId, String clientIp) {
    // SQS 미설정 시 fallback
    if (voteProducer == null) {
        return self.votePessimistic(entryId, memberId);
    }

    // ── Rate Limiting (Phase 13) ──
    // IP당 1분에 10회 초과 차단
    if (rateLimiterService.isRateLimited("ip:" + clientIp, 10, Duration.ofMinutes(1))) {
        throw new GeneralException(ErrorStatus._TOO_MANY_REQUESTS);
    }
    // 회원당 1분에 5회 초과 차단
    if (rateLimiterService.isRateLimited("member:" + memberId, 5, Duration.ofMinutes(1))) {
        throw new GeneralException(ErrorStatus._TOO_MANY_REQUESTS);
    }

    // ── 기존 로직 ──
    Entry entry = entryRepository.findById(entryId)
        .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));
    validateVote(entry, memberId);
    // ...
}
```

#### 3-1-3. Controller에서 Client IP 전달

```java
// 변경: controller/EntryController.java

@PostMapping("/{entryId}/votes")
public ApiResponse<VoteResponseDto.VoteResult> vote(
        @PathVariable Long entryId,
        @LoginUser Integer memberId,
        HttpServletRequest request) {

    String clientIp = getClientIp(request);
    return ApiResponse.onSuccess(
        voteService.vote(entryId, memberId, clientIp));
}

private String getClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isEmpty()) {
        return xff.split(",")[0].trim();
    }
    return request.getRemoteAddr();
}
```

#### 3-1-4. ErrorStatus 추가

```java
// 변경: global/apiPayload/status/ErrorStatus.java

_TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_001", "너무 많은 요청입니다. 잠시 후 다시 시도해주세요.");
```

---

### Phase 13-2: 비동기 투표 패턴 분석

**목표**: 투표 이벤트 스트림에서 이상 패턴을 감지 (2차 분석)

#### 3-2-1. VoteAuditLog 엔티티

```java
// 신규: entity/VoteAuditLog.java

@Entity
@Table(name = "vote_audit_log",
       indexes = {
           @Index(name = "idx_audit_entry", columnList = "entry_id, voted_at"),
           @Index(name = "idx_audit_ip", columnList = "client_ip, voted_at"),
           @Index(name = "idx_audit_member", columnList = "member_id, voted_at")
       })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class VoteAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    @Column(name = "challenge_id", nullable = false)
    private Long challengeId;

    @Column(name = "member_id", nullable = false)
    private Integer memberId;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "voted_at", nullable = false)
    private LocalDateTime votedAt;

    @Column(name = "suspicious", nullable = false)
    @Builder.Default
    private Boolean suspicious = false;

    @Column(name = "suspicious_reason", length = 200)
    private String suspiciousReason;
}
```

```sql
-- DDL
CREATE TABLE vote_audit_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_id          BIGINT       NOT NULL,
    challenge_id      BIGINT       NOT NULL,
    member_id         INT          NOT NULL,
    client_ip         VARCHAR(45),
    user_agent        VARCHAR(500),
    voted_at          DATETIME(6)  NOT NULL,
    suspicious        BOOLEAN      NOT NULL DEFAULT FALSE,
    suspicious_reason VARCHAR(200),
    INDEX idx_audit_entry (entry_id, voted_at),
    INDEX idx_audit_ip (client_ip, voted_at),
    INDEX idx_audit_member (member_id, voted_at)
);
```

#### 3-2-2. VoteMessage에 메타데이터 추가

```java
// 변경: messaging/VoteMessage.java — 필드 추가

private String clientIp;
private String userAgent;

// create() 팩토리 메서드 확장
public static VoteMessage create(Integer memberId, Long entryId,
                                  Long challengeId, String clientIp,
                                  String userAgent) {
    return VoteMessage.builder()
        .voteId(UUID.randomUUID().toString())
        .memberId(memberId)
        .entryId(entryId)
        .challengeId(challengeId)
        .clientIp(clientIp)
        .userAgent(userAgent)
        .timestamp(Instant.now())
        .build();
}
```

#### 3-2-3. VoteConsumer에서 감사 로그 저장

```java
// 변경: messaging/VoteConsumer.java — handleVote() 내부에 추가

// Vote 저장 성공 후
voteAuditLogRepository.save(VoteAuditLog.builder()
    .entryId(message.getEntryId())
    .challengeId(message.getChallengeId())
    .memberId(message.getMemberId())
    .clientIp(message.getClientIp())
    .userAgent(message.getUserAgent())
    .votedAt(LocalDateTime.ofInstant(message.getTimestamp(), ZoneId.systemDefault()))
    .build());
```

---

### Phase 13-3: 어뷰징 탐지 규칙 엔진

**목표**: 주기적으로 패턴 분석 → 의심 투표 플래그

#### 3-3-1. AbuseDetectionService

```java
// 신규: service/AbuseDetectionService.java

@Slf4j
@Service
@RequiredArgsConstructor
public class AbuseDetectionService {

    private final VoteAuditLogRepository auditLogRepository;

    /**
     * 규칙 1: IP 기반 다중 계정 감지
     *
     * 같은 IP에서 같은 챌린지에 3개 이상 계정이 투표
     * → 다중 계정 의심
     *
     * 쿼리: GROUP BY client_ip, challenge_id HAVING COUNT(DISTINCT member_id) >= 3
     */
    public List<SuspiciousPattern> detectMultiAccount(Long challengeId, Duration window) {
        LocalDateTime since = LocalDateTime.now().minus(window);

        List<Object[]> results = auditLogRepository
            .findMultiAccountPatterns(challengeId, since, 3);

        return results.stream()
            .map(r -> new SuspiciousPattern(
                "MULTI_ACCOUNT",
                String.format("IP %s에서 %d개 계정이 투표", r[0], ((Long) r[1]).intValue()),
                (String) r[0],  // clientIp
                null))
            .toList();
    }

    /**
     * 규칙 2: 봇 패턴 감지 (투표 간격 균일성)
     *
     * 특정 회원의 최근 투표 간격이 너무 균일함
     * → 표준편차 < 500ms = 봇 의심
     *
     * 원리:
     * - 사람: 투표 간격 불규칙 (2초, 15초, 7초, 30초...) → 표준편차 높음
     * - 봇:  투표 간격 균일 (3.01초, 2.99초, 3.02초...) → 표준편차 낮음
     */
    public List<SuspiciousPattern> detectBotPattern(Long challengeId, Duration window) {
        LocalDateTime since = LocalDateTime.now().minus(window);

        List<VoteAuditLog> logs = auditLogRepository
            .findByChallengeIdAndVotedAtAfterOrderByMemberIdAscVotedAtAsc(challengeId, since);

        // 회원별 투표 간격 분석
        Map<Integer, List<VoteAuditLog>> byMember = logs.stream()
            .collect(Collectors.groupingBy(VoteAuditLog::getMemberId));

        List<SuspiciousPattern> patterns = new ArrayList<>();

        for (var entry : byMember.entrySet()) {
            List<VoteAuditLog> memberLogs = entry.getValue();
            if (memberLogs.size() < 5) continue; // 5건 미만은 분석 불가

            List<Long> intervals = calculateIntervals(memberLogs);
            double stdDev = calculateStdDev(intervals);

            if (stdDev < 500) { // 표준편차 500ms 미만 = 봇 의심
                patterns.add(new SuspiciousPattern(
                    "BOT_PATTERN",
                    String.format("memberId=%d, 투표 %d건, 간격 표준편차 %.0fms",
                                  entry.getKey(), memberLogs.size(), stdDev),
                    null,
                    entry.getKey()));
            }
        }

        return patterns;
    }

    /**
     * 규칙 3: Entry 투표 서지 감지
     *
     * 특정 Entry에 최근 10분간 투표가 일 평균의 5배 초과
     * → 조직 투표 의심
     */
    public List<SuspiciousPattern> detectVoteSurge(Long challengeId, Duration window) {
        LocalDateTime since = LocalDateTime.now().minus(window);

        // 최근 윈도우 내 Entry별 투표 수
        List<Object[]> recentCounts = auditLogRepository
            .countByEntryInWindow(challengeId, since);

        // 챌린지 전체 기간 Entry별 일 평균 투표 수
        List<Object[]> dailyAverages = auditLogRepository
            .avgDailyVotesByEntry(challengeId);

        Map<Long, Double> avgMap = dailyAverages.stream()
            .collect(Collectors.toMap(
                r -> (Long) r[0],
                r -> ((Number) r[1]).doubleValue()));

        List<SuspiciousPattern> patterns = new ArrayList<>();

        for (Object[] row : recentCounts) {
            Long entryId = (Long) row[0];
            long recentCount = (Long) row[1];
            double dailyAvg = avgMap.getOrDefault(entryId, 1.0);

            // 윈도우 크기에 비례한 기대값 대비 5배 초과
            double expectedInWindow = dailyAvg * window.toMinutes() / (24.0 * 60);
            double threshold = Math.max(expectedInWindow * 5, 10); // 최소 10표

            if (recentCount > threshold) {
                patterns.add(new SuspiciousPattern(
                    "VOTE_SURGE",
                    String.format("entryId=%d, 최근 %d분 %d표 (기대값 %.1f)",
                                  entryId, window.toMinutes(), recentCount, expectedInWindow),
                    null,
                    null));
            }
        }

        return patterns;
    }

    // 투표 간격 계산 (밀리초)
    private List<Long> calculateIntervals(List<VoteAuditLog> logs) {
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < logs.size(); i++) {
            long diff = Duration.between(
                logs.get(i - 1).getVotedAt(),
                logs.get(i).getVotedAt()).toMillis();
            intervals.add(diff);
        }
        return intervals;
    }

    // 표준편차 계산
    private double calculateStdDev(List<Long> values) {
        if (values.size() < 2) return Double.MAX_VALUE;
        double mean = values.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .average().orElse(0);
        return Math.sqrt(variance);
    }
}
```

```java
// 신규: dto/SuspiciousPattern.java

public record SuspiciousPattern(
    String type,           // MULTI_ACCOUNT, BOT_PATTERN, VOTE_SURGE
    String description,
    String clientIp,       // nullable
    Integer memberId       // nullable
) {}
```

#### 3-3-2. AbuseDetectionScheduler

```java
// 신규: service/AbuseDetectionScheduler.java

@Slf4j
@Component
@RequiredArgsConstructor
public class AbuseDetectionScheduler {

    private final AbuseDetectionService abuseDetectionService;
    private final ChallengeRepository challengeRepository;
    private final VoteAuditLogRepository auditLogRepository;

    /**
     * 10분마다 활성 챌린지의 어뷰징 패턴 분석
     */
    @Scheduled(fixedRate = 600_000)
    @SchedulerLock(name = "abuse-detection",
                   lockAtMostFor = "PT5M",
                   lockAtLeastFor = "PT1M")
    public void detectAbuse() {
        var activeChallenges = challengeRepository.findByStatus(ChallengeStatus.ACTIVE);

        for (var challenge : activeChallenges) {
            Long challengeId = challenge.getId();
            Duration window = Duration.ofMinutes(30);

            // 규칙 1: 다중 계정
            var multiAccount = abuseDetectionService.detectMultiAccount(challengeId, window);
            // 규칙 2: 봇 패턴
            var botPatterns = abuseDetectionService.detectBotPattern(challengeId, window);
            // 규칙 3: 투표 서지
            var surges = abuseDetectionService.detectVoteSurge(challengeId, window);

            List<SuspiciousPattern> all = new ArrayList<>();
            all.addAll(multiAccount);
            all.addAll(botPatterns);
            all.addAll(surges);

            if (!all.isEmpty()) {
                log.warn("[AbuseDetection] Challenge {}: {} suspicious patterns detected",
                         challengeId, all.size());
                for (var pattern : all) {
                    log.warn("[AbuseDetection] {}: {}", pattern.type(), pattern.description());
                    flagSuspiciousVotes(pattern);
                }
            }
        }
    }

    /**
     * 의심 투표에 suspicious 플래그 설정
     */
    private void flagSuspiciousVotes(SuspiciousPattern pattern) {
        if (pattern.memberId() != null) {
            auditLogRepository.flagByMemberId(pattern.memberId(), pattern.type());
        }
        if (pattern.clientIp() != null) {
            auditLogRepository.flagByClientIp(pattern.clientIp(), pattern.type());
        }
    }
}
```

#### 3-3-3. VoteAuditLogRepository 쿼리

```java
// 신규: repository/VoteAuditLogRepository.java

public interface VoteAuditLogRepository extends JpaRepository<VoteAuditLog, Long> {

    // 봇 분석용: 챌린지별 시간순 로그
    List<VoteAuditLog> findByChallengeIdAndVotedAtAfterOrderByMemberIdAscVotedAtAsc(
        Long challengeId, LocalDateTime since);

    // 다중 계정 감지: IP별 고유 회원 수
    @Query("""
        SELECT v.clientIp, COUNT(DISTINCT v.memberId) as cnt
        FROM VoteAuditLog v
        WHERE v.challengeId = :challengeId AND v.votedAt > :since
        GROUP BY v.clientIp
        HAVING COUNT(DISTINCT v.memberId) >= :threshold
        """)
    List<Object[]> findMultiAccountPatterns(
        @Param("challengeId") Long challengeId,
        @Param("since") LocalDateTime since,
        @Param("threshold") int threshold);

    // 투표 서지: Entry별 윈도우 내 투표 수
    @Query("""
        SELECT v.entryId, COUNT(v) as cnt
        FROM VoteAuditLog v
        WHERE v.challengeId = :challengeId AND v.votedAt > :since
        GROUP BY v.entryId
        """)
    List<Object[]> countByEntryInWindow(
        @Param("challengeId") Long challengeId,
        @Param("since") LocalDateTime since);

    // 일 평균 투표 수
    @Query(value = """
        SELECT entry_id,
               COUNT(*) / GREATEST(DATEDIFF(NOW(), MIN(voted_at)), 1) as daily_avg
        FROM vote_audit_log
        WHERE challenge_id = :challengeId
        GROUP BY entry_id
        """, nativeQuery = true)
    List<Object[]> avgDailyVotesByEntry(@Param("challengeId") Long challengeId);

    // 의심 플래그 업데이트
    @Modifying
    @Query("""
        UPDATE VoteAuditLog v
        SET v.suspicious = true, v.suspiciousReason = :reason
        WHERE v.memberId = :memberId AND v.suspicious = false
        """)
    int flagByMemberId(@Param("memberId") Integer memberId, @Param("reason") String reason);

    @Modifying
    @Query("""
        UPDATE VoteAuditLog v
        SET v.suspicious = true, v.suspiciousReason = :reason
        WHERE v.clientIp = :clientIp AND v.suspicious = false
        """)
    int flagByClientIp(@Param("clientIp") String clientIp, @Param("reason") String reason);
}
```

---

## 4. 수정 대상 파일 목록

| 파일 | 작업 |
|------|------|
| `service/VoteService.java` | Rate Limiting 추가, clientIp 파라미터 추가 |
| `controller/EntryController.java` | HttpServletRequest에서 IP 추출 |
| `messaging/VoteMessage.java` | clientIp, userAgent 필드 추가 |
| `messaging/VoteConsumer.java` | 감사 로그 저장 추가 |
| `global/apiPayload/status/ErrorStatus.java` | `_TOO_MANY_REQUESTS` 추가 |
| **신규** `service/RateLimiterService.java` | Redis Sliding Window |
| **신규** `service/AbuseDetectionService.java` | 3가지 탐지 규칙 |
| **신규** `service/AbuseDetectionScheduler.java` | 10분 주기 분석 |
| **신규** `entity/VoteAuditLog.java` | 감사 로그 엔티티 |
| **신규** `repository/VoteAuditLogRepository.java` | 분석 쿼리 |
| **신규** `dto/SuspiciousPattern.java` | 탐지 결과 DTO |
| **DB** `vote_audit_log` 테이블 | DDL 실행 |

---

## 5. 검증 계획

### 5-1. Rate Limiting 테스트

```
시나리오 1: IP Rate Limit
  1. 같은 IP에서 10회 투표 → 모두 성공
  2. 11번째 투표 → 429 Too Many Requests
  3. 1분 후 → 다시 투표 가능

시나리오 2: Member Rate Limit
  1. 같은 회원이 5개 Entry에 투표 → 모두 성공
  2. 6번째 Entry 투표 → 429 Too Many Requests

시나리오 3: 정상 사용자 오탐 없음 (핵심!)
  1. 100명이 각자 1~2회 투표 → 전원 성공
  2. 차단된 사용자 0명 확인
```

### 5-2. 어뷰징 탐지 테스트

```
시나리오 4: 다중 계정 감지
  1. 같은 IP(1.2.3.4)에서 계정 A, B, C가 같은 챌린지에 투표
  2. 10분 후 스케줄러 실행
  3. MULTI_ACCOUNT 패턴 감지 확인
  4. vote_audit_log.suspicious = true 확인

시나리오 5: 봇 패턴 감지
  1. 회원 X가 정확히 3초 간격으로 10개 Entry에 투표
  2. 10분 후 스케줄러 실행
  3. BOT_PATTERN 감지 확인 (표준편차 < 500ms)
  4. 대조군: 회원 Y가 불규칙 간격(2~30초)으로 10회 투표 → 미감지

시나리오 6: 투표 서지 감지
  1. Entry Z에 30분 내 50표 집중 (일 평균 5표)
  2. 10분 후 스케줄러 실행
  3. VOTE_SURGE 감지 확인

시나리오 7: 마감 직전 자연 트래픽 증가 (오탐 방지)
  1. 마감 10분 전 전체 투표량 자연 증가 (모든 Entry에 분산)
  2. 특정 Entry에만 집중되지 않음
  3. VOTE_SURGE 미감지 확인 (중요!)
```

### 5-3. k6 부하 테스트 (성능 오버헤드 측정)

```javascript
// k6 시나리오: Rate Limiting 오버헤드 측정
// Phase 12 완료 상태에서:
//   Before: Rate Limiting 없이 투표 p95
//   After:  Rate Limiting 추가 후 투표 p95
//   차이: +5ms 이내 확인

export const options = {
    scenarios: {
        normal_voting: {
            executor: 'constant-vus',
            vus: 50,
            duration: '2m',
        },
    },
    thresholds: {
        'http_req_duration{name:vote}': ['p(95)<700'],  // 658ms + 오버헤드
    },
};
```

---

## 6. 아키텍처 변경 다이어그램

```
[Before — Phase 12까지]
투표 요청 → DB 중복 체크 → Redis 랭킹 → SQS 전송 → 응답

[After — Phase 13]
투표 요청
  ↓
  ├─ Redis Rate Limit 체크 (1 RTT, ~2ms)  ← 신규
  │    └─ 초과 시 429 응답
  ↓
  ├─ DB 중복 체크 (기존)
  ├─ Redis 랭킹 업데이트 (기존)
  ├─ SQS 전송 + clientIp/userAgent 포함  ← 변경
  └─ 응답

SQS Consumer (비동기)
  ↓
  ├─ Vote DB 저장 (기존)
  └─ VoteAuditLog 저장  ← 신규

AbuseDetectionScheduler (10분 주기)  ← 신규
  ↓
  ├─ 규칙 1: 다중 계정 (IP GROUP BY)
  ├─ 규칙 2: 봇 패턴 (간격 표준편차)
  ├─ 규칙 3: 투표 서지 (윈도우 vs 일 평균)
  └─ 의심 투표 플래그 → 관리자 검토
```

---

## 7. 포트폴리오 서술 포인트

이 Phase를 구현하면 다음과 같이 서술할 수 있다:

> **"DB UNIQUE 제약만으로는 막을 수 없는 다중 계정, 봇, 조직 투표를 실시간으로 탐지하는 시스템을 설계했습니다."**
>
> 구체적으로:
> - Redis Sliding Window Counter로 IP/회원당 Rate Limiting (오버헤드 +5ms 이내)
> - 투표 간격 표준편차 분석으로 봇 패턴 자동 감지
> - IP 기반 다중 계정 GROUP BY 분석
> - Entry별 투표 서지 감지 (일 평균 대비 5배 임계값)
> - 정상 투표 오탐율 0% (마감 직전 자연 트래픽 증가 시에도 오탐 없음)
>
> 검증 결과: 봇 패턴(3초 간격 균일 투표) **100% 탐지**, 정상 사용자 차단 **0건**

---

## 8. 확장 가능성 (구현하지 않되, 면접에서 언급)

```
Level 1 (구현): Rate Limiting + 패턴 분석 + 플래그
Level 2 (언급): 실시간 차단 (Redis Blacklist)
Level 3 (언급): ML 기반 이상 탐지 (Isolation Forest)
Level 4 (언급): 관리자 대시보드 (Grafana + suspicious 메트릭)
```

> "현재는 규칙 기반 탐지를 구현했지만, 데이터가 쌓이면 투표 행동 Feature를 추출하여 Isolation Forest 같은 비지도 학습으로 이상 탐지를 고도화할 수 있습니다."