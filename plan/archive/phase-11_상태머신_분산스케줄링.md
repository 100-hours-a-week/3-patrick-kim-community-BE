# Phase 11: 챌린지 라이프사이클 상태 머신 + 분산 스케줄링

> Phase 12(마감 랭킹 확정)의 기반. FINALIZING 상태가 있어야 마감 파이프라인을 트리거할 수 있다.

---

## 1. 현재 문제 분석

### 1-1. ChallengeStatus가 너무 단순하다

```java
// 현재: entity/enums/ChallengeStatus.java
public enum ChallengeStatus {
    UPCOMING, ACTIVE, ENDED  // 3개 상태, 전이 규칙 없음
}
```

```java
// 현재: Challenge.java
public void changeStatus(ChallengeStatus status) {
    this.status = status;  // 아무 상태로든 전환 가능 → 위험
}
```

**문제**: ACTIVE → ENDED 직접 전환 시, SQS 큐에 남은 미처리 투표가 유실될 수 있다.

### 1-2. 마감 자동 처리가 없다

현재 챌린지 상태 변경은 수동(API 호출)으로만 가능. `endAt` 시간이 지나도 ACTIVE 상태가 유지된다.

### 1-3. 다중 서버 대비가 안 되어 있다

```java
// 현재: VoteConsistencyScheduler.java
@Scheduled(fixedRate = 300000) // 분산 락 없음
public void verifyConsistency() { ... }
```

서버 2대면 같은 스케줄러가 동시 실행된다.

---

## 2. 목표 수치

| 항목 | Before | After | 측정 방법 |
|------|--------|-------|----------|
| 상태 전이 검증 | 없음 (아무 전이 가능) | 허용된 전이만 가능 | 단위 테스트 |
| 마감 자동 처리 | 수동 | 자동 (±10초 정확도) | k6 시나리오 + 로그 확인 |
| 분산 환경 중복 실행 | 미대비 | exactly-once 보장 | 2-instance 동시 실행 테스트 |
| 실패 복구 | 없음 | 자동 복구 (30분 타임아웃) | 강제 종료 후 재시작 테스트 |

---

## 3. 구현 계획

### 3-1. ChallengeStatus 확장

```java
// 변경: entity/enums/ChallengeStatus.java
public enum ChallengeStatus {
    UPCOMING,           // 시작 전
    ACTIVE,             // 진행 중 (투표 가능)
    CLOSING_SOON,       // 마감 임박 (마감 10분 전, 투표 가능)
    FINALIZING,         // 마감 처리 중 (투표 차단, 큐 드레인 중)
    ENDED,              // 랭킹 확정 완료
    RESULT_ANNOUNCED;   // 결과 발표 완료

    // 허용된 전이 정의
    private static final Map<ChallengeStatus, Set<ChallengeStatus>> ALLOWED = Map.of(
        UPCOMING,         Set.of(ACTIVE),
        ACTIVE,           Set.of(CLOSING_SOON),
        CLOSING_SOON,     Set.of(FINALIZING),
        FINALIZING,       Set.of(ENDED),
        ENDED,            Set.of(RESULT_ANNOUNCED)
    );

    public boolean canTransitionTo(ChallengeStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }
}
```

**DB 마이그레이션**: `ALTER TABLE challenge MODIFY COLUMN status VARCHAR(20)` — 기존 ENDED 데이터는 그대로 유지 (하위 호환)

### 3-2. Challenge 엔티티 상태 전이 메서드

```java
// 변경: entity/Challenge.java

// 기존 changeStatus()를 대체
public void transitionTo(ChallengeStatus next) {
    if (!this.status.canTransitionTo(next)) {
        throw new IllegalStateException(
            String.format("챌린지 상태 전이 불가: %s → %s (challengeId=%d)",
                          this.status, next, this.id));
    }
    this.status = next;
    this.statusChangedAt = LocalDateTime.now();
}

// 투표 가능 여부 (ACTIVE, CLOSING_SOON만 투표 가능)
public boolean isVotable() {
    return this.status == ChallengeStatus.ACTIVE
        || this.status == ChallengeStatus.CLOSING_SOON;
}
```

**필드 추가**: `statusChangedAt` (실패 복구용 타임스탬프)

### 3-3. VoteService 투표 차단 조건 변경

```java
// 변경: service/VoteService.java — validateVote()

private void validateVote(Entry entry, Integer memberId) {
    // Before: challenge.getStatus() != ChallengeStatus.ACTIVE
    // After:  challenge.isVotable() 사용 (ACTIVE + CLOSING_SOON 허용)
    if (!entry.getChallenge().isVotable()) {
        throw new GeneralException(ErrorStatus._BAD_REQUEST);
    }
    if (entry.getMember().getId().equals(memberId)) {
        throw new GeneralException(ErrorStatus._BAD_REQUEST);
    }
}
```

### 3-4. 라이프사이클 스케줄러

```java
// 신규: service/ChallengeLifecycleScheduler.java

@Slf4j
@Component
@RequiredArgsConstructor
public class ChallengeLifecycleScheduler {

    private final ChallengeRepository challengeRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 10초마다 챌린지 상태 자동 전이 체크
     *
     * ShedLock으로 다중 서버에서 exactly-once 실행 보장
     */
    @Scheduled(fixedRate = 10_000)
    @SchedulerLock(name = "challenge-lifecycle",
                   lockAtMostFor = "PT30S",
                   lockAtLeastFor = "PT5S")
    public void processLifecycle() {
        LocalDateTime now = LocalDateTime.now();

        // UPCOMING → ACTIVE (시작 시간 도래)
        challengeRepository.findByStatusAndStartAtBefore(UPCOMING, now)
            .forEach(c -> transition(c, ACTIVE));

        // ACTIVE → CLOSING_SOON (마감 10분 전)
        challengeRepository.findByStatusAndEndAtBefore(ACTIVE, now.plusMinutes(10))
            .forEach(c -> transition(c, CLOSING_SOON));

        // CLOSING_SOON → FINALIZING (마감 시간 도래)
        challengeRepository.findByStatusAndEndAtBefore(CLOSING_SOON, now)
            .forEach(c -> transition(c, FINALIZING));
    }

    @Transactional
    public void transition(Challenge challenge, ChallengeStatus next) {
        challenge.transitionTo(next);
        challengeRepository.save(challenge);

        eventPublisher.publishEvent(
            new ChallengeStatusChangedEvent(challenge.getId(), next));

        log.info("[Lifecycle] Challenge {} transitioned to {}",
                 challenge.getId(), next);
    }
}
```

### 3-5. ChallengeRepository 쿼리 추가

```java
// 변경: repository/ChallengeRepository.java

// 상태 + 시작시간 조건
List<Challenge> findByStatusAndStartAtBefore(ChallengeStatus status, LocalDateTime time);

// 상태 + 종료시간 조건
List<Challenge> findByStatusAndEndAtBefore(ChallengeStatus status, LocalDateTime time);

// 실패 복구용: 상태 + statusChangedAt 조건
List<Challenge> findByStatusAndStatusChangedAtBefore(
    ChallengeStatus status, LocalDateTime time);
```

### 3-6. ShedLock 분산 락 설정

```groovy
// 변경: build.gradle — 의존성 추가
implementation 'net.javacrumbs.shedlock:shedlock-spring:6.2.0'
implementation 'net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.2.0'
```

```java
// 신규: global/config/ShedLockConfig.java

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class ShedLockConfig {
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );
    }
}
```

```sql
-- DB 마이그레이션: ShedLock 테이블
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL
);
```

### 3-7. 이벤트 기반 후속 작업

```java
// 신규: event/ChallengeStatusChangedEvent.java

public record ChallengeStatusChangedEvent(
    Long challengeId,
    ChallengeStatus newStatus
) {}
```

```java
// 신규: event/ChallengeEventListener.java

@Slf4j
@Component
@RequiredArgsConstructor
public class ChallengeEventListener {

    private final RankingFinalizationService rankingFinalizationService;

    @Async
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onStatusChanged(ChallengeStatusChangedEvent event) {
        switch (event.newStatus()) {
            case CLOSING_SOON ->
                log.info("[Event] Challenge {} closing soon", event.challengeId());
            case FINALIZING ->
                // Phase 12에서 구현: 랭킹 확정 파이프라인 트리거
                rankingFinalizationService.finalize(event.challengeId());
            case ENDED ->
                log.info("[Event] Challenge {} ended, ranking finalized", event.challengeId());
            default -> {}
        }
    }
}
```

### 3-8. 실패 복구 (서버 재시작 시)

```java
// 신규: ChallengeLifecycleScheduler.java에 추가

/**
 * 앱 시작 시 멈춰 있는 FINALIZING 챌린지 복구
 * 30분 이상 FINALIZING = 처리 중 서버가 죽은 것
 */
@PostConstruct
public void recoverStuckChallenges() {
    List<Challenge> stuck = challengeRepository
        .findByStatusAndStatusChangedAtBefore(
            FINALIZING, LocalDateTime.now().minusMinutes(30));

    for (Challenge c : stuck) {
        log.warn("[Recovery] Stuck FINALIZING challenge found: id={}", c.getId());
        eventPublisher.publishEvent(
            new ChallengeStatusChangedEvent(c.getId(), FINALIZING));
    }
}
```

### 3-9. 기존 VoteConsistencyScheduler에 ShedLock 적용

```java
// 변경: service/VoteConsistencyScheduler.java

@Scheduled(fixedRate = 300000)
@SchedulerLock(name = "vote-consistency",
               lockAtMostFor = "PT5M",
               lockAtLeastFor = "PT1M")
public void verifyConsistency() {
    // 기존 로직 그대로
}
```

---

## 4. 수정 대상 파일 목록

| 파일 | 작업 |
|------|------|
| `entity/enums/ChallengeStatus.java` | 상태 추가 + 전이 규칙 |
| `entity/Challenge.java` | `transitionTo()`, `isVotable()`, `statusChangedAt` 필드 |
| `service/VoteService.java` | `validateVote()` 조건 변경 |
| `service/VoteConsistencyScheduler.java` | `@SchedulerLock` 추가 |
| `repository/ChallengeRepository.java` | 쿼리 메서드 3개 추가 |
| `build.gradle` | ShedLock 의존성 |
| `application-*.yml` | (변경 없음) |
| **신규** `service/ChallengeLifecycleScheduler.java` | 라이프사이클 스케줄러 |
| **신규** `global/config/ShedLockConfig.java` | ShedLock 설정 |
| **신규** `event/ChallengeStatusChangedEvent.java` | 이벤트 레코드 |
| **신규** `event/ChallengeEventListener.java` | 이벤트 리스너 |
| **DB** `shedlock` 테이블 | DDL 실행 |
| **DB** `challenge.status` | VARCHAR 확장 (이미 VARCHAR(20)이면 불필요) |
| **DB** `challenge.status_changed_at` | 컬럼 추가 |

---

## 5. 검증 계획

### 5-1. 단위 테스트

```java
// ChallengeStatus 전이 규칙 테스트
@Test void ACTIVE에서_ENDED로_직접_전이_불가() {
    Challenge challenge = createActive();
    assertThrows(IllegalStateException.class,
        () -> challenge.transitionTo(ENDED));
}

@Test void ACTIVE에서_CLOSING_SOON으로_전이_가능() {
    Challenge challenge = createActive();
    challenge.transitionTo(CLOSING_SOON);
    assertEquals(CLOSING_SOON, challenge.getStatus());
}

@Test void FINALIZING_상태에서_투표_불가() {
    Challenge challenge = createFinalizing();
    assertFalse(challenge.isVotable());
}
```

### 5-2. 통합 테스트

```
시나리오 1: 정상 라이프사이클
  1. 챌린지 생성 (UPCOMING, startAt=now+10초, endAt=now+2분)
  2. 10초 후 → ACTIVE 자동 전환 확인
  3. endAt-10분 전 → CLOSING_SOON 전환 (이 시나리오에서는 바로 전환)
  4. endAt 도래 → FINALIZING 전환 확인
  5. Phase 12 파이프라인 완료 → ENDED 확인

시나리오 2: FINALIZING 중 서버 재시작
  1. 챌린지를 FINALIZING으로 설정
  2. statusChangedAt을 31분 전으로 설정
  3. 앱 재시작
  4. @PostConstruct에서 자동 복구 확인 (로그)

시나리오 3: 분산 락 검증
  1. docker-compose로 앱 2대 실행
  2. 같은 챌린지의 마감 도래
  3. 1대만 FINALIZING 전환 실행 확인 (로그)
```

### 5-3. k6 부하 테스트

```javascript
// k6 시나리오: 마감 시점 투표 차단 확인
// 1. ACTIVE 상태에서 투표 → 200 OK
// 2. FINALIZING 전환 후 투표 → 400 Bad Request
// 3. 전환 시점 전후의 응답 확인
```

---

## 6. 아키텍처 변경 다이어그램

```
[Before]
@Scheduled(5분) → verifyConsistency()  (분산 락 없음)
Challenge: UPCOMING → ACTIVE → ENDED   (수동 전환, 검증 없음)

[After]
@Scheduled(10초) + ShedLock → processLifecycle()  (exactly-once)
@Scheduled(5분)  + ShedLock → verifyConsistency()  (exactly-once)

Challenge: UPCOMING → ACTIVE → CLOSING_SOON → FINALIZING → ENDED → RESULT_ANNOUNCED
                                                    ↓
                                              이벤트 발행
                                                    ↓
                                        Phase 12 파이프라인 트리거
```