# Round 4: 비동기 투표 파이프라인 장애 대응

> **질문**: "비동기 파이프라인에서 장애가 발생하면 데이터 정합성을 어떻게 보장하는가?"
> **목표**: 장애 시나리오 식별 → 보상 트랜잭션 + Circuit Breaker 도입 → 검증

---

## 1. 현재 아키텍처와 장애 시나리오

### 현재 투표 흐름

```
Client → API → Redis Pipeline (SADD + ZINCRBY + ZSCORE) → SQS Fire & Forget → Response
                                                              ↓
                                                     Consumer → DB (Vote INSERT + Entry UPDATE)
```

### 식별된 장애 시나리오 4가지

#### 장애 1: SQS 전송 실패 → 투표 유실

```
Redis: SADD ✓ ZINCRBY ✓ (투표 기록 + 랭킹 반영)
SQS:   전송 실패 ✗
DB:    메시지 안 옴 → Vote 영원히 미저장

→ 사용자는 투표 성공으로 보이지만 DB에 없음
→ ConsistencyScheduler가 5분 후 DB 기준으로 Redis 덮어씀
→ 투표 사라짐!
```

**해결**: SQS 전송 실패 시 Redis 보상 트랜잭션 (SREM + ZINCRBY -1)

#### 장애 2: Redis 전체 장애 → 투표 API 전체 장애

```
현재 코드:
  voteAsync() → rankingRedisService.recordAndIncrementPipelined(...)
  → RedisConnectionFailureException → 500 에러 → 투표 불가

SQS 없을 때 Fallback은 있지만 (Pessimistic Lock)
Redis 장애 시 Fallback은 없음!
```

**해결**: Resilience4j Circuit Breaker → Redis 장애 감지 → 자동으로 Pessimistic Lock 전환

#### 장애 3: Redis 재시작 → 중복 투표 방어 붕괴

```
Redis 재시작 시:
  ranking:challenge:{id} → Cache Warming으로 복구 ✓
  votes:{entryId} SET → 복구 안 됨 ✗

→ SADD 중복 체크 데이터 소실
→ 이미 투표한 사용자가 다시 투표 가능
→ API 응답은 성공, Consumer에서 DB UK로 최종 방어
```

**해결**: 이건 Redis 특성상 완전 해결 불가 → DB UK를 최종 방어선으로 명시적 설계 + 문서화

#### 장애 4: ConsistencyScheduler의 한계

```
Redis 100표, DB 99표 → "SQS 지연이겠지" → 30초 대기
BUT 실제로는 SQS 전송 실패 → 30초 후에도 불일치 → DB 기준 덮어씀 → 투표 유실

→ "SQS 처리 지연"과 "SQS 전송 실패"를 구분 못 함
```

**해결**: 보상 트랜잭션으로 장애 1을 해결하면 이 문제도 대부분 해소됨

---

## 2. 해결 계획

### Phase A: 보상 트랜잭션 (SQS 전송 실패 시 Redis 롤백)

**변경 파일**: `VoteService.voteAsync()`

```java
// Before: Fire & Forget (롤백 없음)
voteProducer.sendVote(message);

// After: 보상 트랜잭션 추가
try {
    voteProducer.sendVote(message);
} catch (Exception e) {
    // SQS 전송 실패 → Redis 보상 (롤백)
    rankingRedisService.decrementVote(challengeId, entryId);
    rankingRedisService.removeVoteRecord(entryId, memberId);
    log.error("[VoteAsync] SQS send failed, Redis compensated", e);
    throw new GeneralException(ErrorStatus._INTERNAL_SERVER_ERROR);
}
```

**검증**: SQS 전송 실패 시뮬레이션 → Redis 롤백 확인

### Phase B: Circuit Breaker (Redis 장애 시 자동 Fallback)

**의존성 추가**: `build.gradle`

```gradle
implementation 'org.springframework.boot:spring-boot-starter-aop'
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
```

**핵심 설계**:

```
                  ┌─────────────────────────────────────┐
                  │         Circuit Breaker              │
                  │                                     │
  투표 요청 ──→  │  CLOSED ──────→ Redis + SQS (비동기) │
                  │    │                                │
                  │    │ failure rate ≥ 50%              │
                  │    ▼                                │
                  │  OPEN ────────→ Pessimistic Lock    │
                  │    │            (동기, 느리지만 안전) │
                  │    │ 30초 대기                       │
                  │    ▼                                │
                  │  HALF_OPEN ──→ Redis 테스트          │
                  │    │            (3개 요청만)         │
                  │    │ 성공 → CLOSED / 실패 → OPEN    │
                  └─────────────────────────────────────┘
```

**Circuit Breaker 설정 근거**:

| 설정 | 값 | 근거 |
|------|-----|------|
| failureRateThreshold | 50% | 절반 이상 실패 시 Redis 불안정 판단 |
| slidingWindowSize | 10 | 최근 10건으로 판단 (빠른 감지) |
| waitDurationInOpenState | 30초 | Redis 일시적 불안정 복구 대기 |
| permittedNumberOfCallsInHalfOpenState | 3 | 3건으로 복구 확인 |
| slowCallDurationThreshold | 2초 | Redis 응답 2초 초과 = slow call |
| slowCallRateThreshold | 80% | slow call 80% 이상 = 장애 |

**변경 파일**: `VoteService.java`

```java
@CircuitBreaker(name = "redisVote", fallbackMethod = "voteFallback")
public VoteResponseDto.VoteResult voteAsync(Long entryId, Integer memberId) {
    // Redis + SQS 비동기 투표 (기존 로직)
}

// Circuit Breaker OPEN 시 자동 호출
public VoteResponseDto.VoteResult voteFallback(Long entryId, Integer memberId, Exception e) {
    log.warn("[CircuitBreaker] Redis unavailable, falling back to pessimistic: {}", e.getMessage());
    return self.votePessimistic(entryId, memberId);
}
```

**application-dev.yml 설정 추가**:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      redisVote:
        failure-rate-threshold: 50
        sliding-window-size: 10
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 80
```

### Phase C: 포트폴리오 문서 작성

`portfolio/02_비동기_파이프라인_정합성_v1.md` (기존 02 대체)

내용:
1. 비동기 전환 후 4가지 장애 시나리오 식별
2. 보상 트랜잭션 설계 (SQS 실패 → Redis 롤백)
3. Circuit Breaker 도입 (Redis 장애 → Pessimistic Lock Fallback)
4. 정합성 보장의 다층 방어 설계
   - L1: Redis SADD (API 레벨 중복 차단)
   - L2: DB UK (Consumer 레벨 최종 방어)
   - L3: ConsistencyScheduler (5분 주기 정합성 검증)
   - L4: Circuit Breaker (Redis 장애 시 동기 Fallback)

---

## 3. 최종 SP2 스토리라인

```
[1막] 비동기 전환 성공
  Hot Spot Lock 병목 → Redis + SQS 비동기로 전환
  → 투표 p95: 1.73s → 50ms (97% 개선) ✓

[2막] 장애 시나리오 식별
  "비동기는 빠르지만, 장애 시 데이터가 사라질 수 있다"
  → SQS 전송 실패, Redis 장애, 중복 방어 붕괴, 정합성 불일치

[3막] 보상 트랜잭션 + Circuit Breaker
  → SQS 실패 시 Redis 롤백으로 데이터 유실 방지
  → Redis 장애 시 자동으로 Pessimistic Lock Fallback
  → SP1의 Pessimistic Lock이 "Fallback 전략"으로 다시 활용

[핵심 메시지]
"비동기 시스템은 빠르지만, 장애 대응 없이는 불완전하다.
보상 트랜잭션으로 데이터 유실을 방지하고,
Circuit Breaker로 인프라 장애에도 서비스를 유지했다."
```
