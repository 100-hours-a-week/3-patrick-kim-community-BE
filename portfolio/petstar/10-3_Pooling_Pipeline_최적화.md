# Phase 10-3: Lettuce 풀링 + Redis Pipeline + 정합성 검증

> **목표**: Hybrid 전략 (DB 중복체크 + Redis 랭킹 + SQS 비동기) 배포 후 추가 최적화
> **핵심 성과**: p95 688ms → 658ms (Pooling + Pipeline), 정합성 검증 배치 도입

---

## 1. 배경: Phase 10-2b Hybrid 전략 실측

### 1.1 Hybrid 전략이란?

Phase 10-2에서 Redis 중복체크로 전환했을 때, Lettuce 단일 커넥션 직렬화로 인해
오히려 성능이 악화(516ms → 1.3s)되는 **성능 역전 현상**이 발생했다.

이를 해결하기 위해 각 컴포넌트의 강점을 활용하는 Hybrid 전략을 도입:

```
하이브리드 전략 (Phase 10-2b):
─────────────────────────────────────────
중복 체크: DB (HikariCP 30개 커넥션 병렬 처리)
랭킹:     Redis (ZINCRBY + ZSCORE = 2회 호출)
DB 저장:  SQS → Consumer (비동기)

→ DB의 병렬 처리 능력 + Redis의 속도 + SQS의 비동기성
```

### 1.2 Hybrid 기본 성능 (Step 1 측정)

배포 후 50명 동시 투표 테스트:

```
테스트 조건: 50 VUs, 같은 Entry에 동시 투표 (Hot Spot)
인프라: EC2 t3.small, RDS db.t3.micro, Redis 7

결과:
| 지표 | 값 |
|------|------|
| 성공률 | 100% (50/50) |
| 에러율 | 0% |
| avg | 600ms |
| p95 | 688ms |
| min | 472ms |
| max | 691ms |
```

---

## 2. 최적화 1: Lettuce 커넥션 풀링

### 2.1 문제 분석

Lettuce는 기본적으로 **단일 커넥션 멀티플렉싱** 방식으로 동작한다.
단일 요청에서는 충분하지만, 고동시성 환경에서는 모든 Redis 명령이 하나의 커넥션을 통해
직렬화되어 병목이 발생한다.

```
Before: 단일 커넥션 멀티플렉싱
─────────────────────────────────
Thread 1 ──┐
Thread 2 ──┼──→ [Single Connection] ──→ Redis
Thread 3 ──┤         (직렬화)
...        │
Thread 50 ─┘


After: 커넥션 풀링 (commons-pool2)
─────────────────────────────────
Thread 1 ──→ [Conn 1] ──┐
Thread 2 ──→ [Conn 2] ──┼──→ Redis
Thread 3 ──→ [Conn 3] ──┤    (병렬 처리)
...                      │
Thread 50 → [Conn 10] ──┘
```

### 2.2 구현

**의존성 추가** (`build.gradle`):
```groovy
implementation 'org.apache.commons:commons-pool2'
```

**설정** (`application-dev.yml`, `application-prod.yml`):
```yaml
spring.data.redis.lettuce.pool:
  max-active: 20    # 최대 커넥션
  max-idle: 10      # 최대 유휴
  min-idle: 5       # 최소 유휴
  max-wait: 1000ms  # 대기 시간
```

`commons-pool2`가 클래스패스에 존재하면 Spring Boot가 자동으로 Lettuce 풀링을 활성화한다.
별도의 Java Config 변경 없이 YAML 설정만으로 적용 가능.

### 2.3 설정값 근거

```
max-active: 20
→ HikariCP(30)보다 작게 설정
→ Redis는 인메모리라 DB보다 적은 커넥션으로 충분
→ Redis 서버의 maxclients(기본 10000) 대비 여유

max-idle: 10 / min-idle: 5
→ 평상시 5개 유지, 스파이크 시 20개까지 확장
→ 유휴 커넥션 정리로 메모리 절약

max-wait: 1000ms
→ 풀 고갈 시 1초까지 대기
→ 그 이상이면 예외 발생 (빠른 실패)
```

---

## 3. 최적화 2: Redis Pipeline

### 3.1 문제 분석

voteAsync()에서 Redis를 2회 호출 (ZINCRBY → ZSCORE)하고 있었다.
각 호출마다 네트워크 RTT가 발생하여, 50명 동시 요청 시 RTT가 누적된다.

```
Before: 개별 명령 (RTT 2회)
─────────────────────────────
App                    Redis
 │── ZINCRBY ────────→ │
 │←── response ─────── │  RTT 1 (~5ms)
 │                      │
 │── ZSCORE ─────────→ │
 │←── response ─────── │  RTT 2 (~5ms)

요청당 Redis RTT: ~10ms
50 동시 요청: 직렬화 시 500ms+


After: Pipeline (RTT 1회)
─────────────────────────────
App                    Redis
 │── ZINCRBY ──┐       │
 │── ZSCORE ───┼─────→ │
 │              │       │
 │←── batch response ── │  RTT 1 (~5ms)

요청당 Redis RTT: ~5ms (50% 감소)
```

### 3.2 구현

**RankingRedisService** — `incrementVoteAndGetScore()` 메서드 추가:
```java
@SuppressWarnings("unchecked")
public int incrementVoteAndGetScore(Long challengeId, Long entryId) {
    String key = RANKING_KEY_PREFIX + challengeId;
    String member = entryId.toString();

    List<Object> results = redisTemplate.executePipelined(new SessionCallback<>() {
        @Override
        public Object execute(RedisOperations operations) throws DataAccessException {
            operations.opsForZSet().incrementScore(key, member, 1);
            operations.opsForZSet().score(key, member);
            return null;
        }
    });

    // results[0] = ZINCRBY 결과, results[1] = ZSCORE 결과
    Double score = (Double) results.get(1);
    return score != null ? score.intValue() : 0;
}
```

**VoteService** — voteAsync()에서 2회 호출을 1회로 교체:
```java
// Before
rankingRedisService.incrementVote(challengeId, entryId);       // ZINCRBY
Double redisScore = rankingRedisService.getScore(challengeId, entryId);  // ZSCORE

// After
int currentVoteCount = rankingRedisService.incrementVoteAndGetScore(challengeId, entryId);
```

### 3.3 Pipeline vs 개별 호출 트레이드오프

```
Pipeline 장점:
- RTT 50% 감소 (2회 → 1회)
- 고동시성에서 직렬화 구간 축소
- 서버 사이드에서도 배치 처리 가능

Pipeline 단점:
- 중간 결과로 분기 불가 (모든 명령이 한 번에 전송)
- 에러 처리가 개별 명령보다 복잡
- 코드 가독성 약간 저하

→ ZINCRBY + ZSCORE는 서로 독립적이므로 Pipeline에 적합
```

---

## 4. 정합성 검증 스케줄러

### 4.1 필요성

비동기 아키텍처에서 Redis와 DB 간 정합성 불일치가 발생할 수 있는 시나리오:

```
1. SQS 전송 실패 → Redis 반영 O, DB 반영 X
2. Consumer 처리 실패 → Redis 반영 O, DB 반영 X
3. Redis 장애/재시작 → Redis 반영 X, DB 반영 O
4. Consumer 데드락 후 롤백 실패
```

### 4.2 구현: VoteConsistencyScheduler

```java
@Scheduled(fixedRate = 300000)  // 5분마다
public void verifyConsistency() {
    // 1. 활성 챌린지 조회
    // 2. 챌린지별 모든 Entry의 Redis vs DB 점수 비교
    // 3. 불일치 처리:
    //    - Redis > DB: SQS 처리 지연 가능 → 30초 대기 후 재검증
    //    - Redis < DB: 데이터 유실 → 즉시 DB 기준 동기화
    //    - 재검증 후에도 불일치 → DB 기준 동기화
}
```

### 4.3 불일치 처리 전략

```
Source of Truth: DB (Vote 테이블)

Case 1: Redis > DB (정상 가능성 높음)
─────────────────────────────────
원인: SQS Consumer가 아직 처리하지 않음
대응: 30초 대기 → 재검증 → 여전히 불일치 시 DB 기준 동기화

Case 2: Redis < DB (비정상)
─────────────────────────────────
원인: Redis 키 손실, 장애 복구 후 데이터 유실
대응: 즉시 DB 기준으로 Redis 동기화 (ZADD)

Case 3: Redis = 0 but DB > 0 (키 손실)
─────────────────────────────────
원인: Redis 재시작 또는 키 만료
대응: DB에서 전체 랭킹 재구축
```

---

## 5. 성능 비교 결과

### 5.1 50명 동시 투표 (Hot Spot)

| 지표 | Step 1 (Hybrid only) | Step 4 (+ Pooling + Pipeline) | 개선율 |
|:-----|:---------------------|:------------------------------|:-------|
| 성공률 | 100% | 100% | - |
| 에러율 | 0% | 0% | - |
| **avg** | 600ms | **580ms** | **-3.3%** |
| **p95** | 688ms | **658ms** | **-4.4%** |
| min | 472ms | 498ms | - |
| **max** | 691ms | **665ms** | **-3.8%** |

### 5.2 개선폭이 작은 이유

```
50 VU 규모에서 개선폭이 작은 이유:

1. 병목 위치
   현재 병목: DB 중복체크 (existsByEntryIdAndMemberId)
   - 50개 요청이 같은 Row를 조회하는 경쟁 상황
   - HikariCP 30개 커넥션으로도 직렬화 발생
   - Redis 최적화로는 이 병목을 줄일 수 없음

2. Redis 호출 비중
   전체 응답시간 중 Redis 비중: ~10ms / 600ms = ~1.7%
   → Pipeline으로 5ms 줄여도 전체에 미치는 영향 미미

3. 더 높은 VU에서의 효과
   100~200 VU에서는 Redis 직렬화 구간이 더 길어지므로
   Pooling + Pipeline 효과가 더 크게 나타날 것으로 예상
```

---

## 6. 전체 투표 성능 변천사

| Phase | 전략 | p95 (50 VUs) | 개선율 | 비고 |
|:------|:-----|:-------------|:-------|:-----|
| Phase 6 | Pessimistic Lock | 1,730ms | 기준 | 동기 DB 저장 |
| Phase 10 | SQS 비동기 | 516ms | **70%↓** | 동기 SQS + DB 체크 |
| Phase 10-2 | Redis 중복체크 | 1,300ms | 역전 | Lettuce 단일 커넥션 병목 |
| Phase 10-2b | Hybrid (DB+Redis+SQS) | 688ms | **60%↓** | DB 병렬 + Redis 2회 |
| **Phase 10-3** | **+ Pooling + Pipeline** | **658ms** | **62%↓** | 커넥션 풀 + 파이프라인 |

---

## 7. 핵심 교훈

### 7.1 최적화는 병목 지점에 집중

```
"Redis를 빠르게 해도, 병목이 DB에 있으면 의미 없다"

현재 병목 분석:
- DB 중복체크: ~500ms (전체의 ~80%) ← 주요 병목
- Redis 호출: ~10ms (전체의 ~1.7%)
- SQS 전송: ~0ms (Fire & Forget)
- Entry 조회: ~50ms (전체의 ~8%)
- 네트워크 오버헤드: ~40ms

→ 다음 최적화: DB 중복체크를 Redis SET으로 전환 (Pooling 적용으로 실현 가능)
```

### 7.2 인프라 제약 인식

```
t3.small (2 vCPU, 2GB RAM) + db.t3.micro 환경에서
50명 동시 Hot Spot 투표를 658ms로 처리하는 것은 합리적인 수준

실제 운영 시나리오에서는:
- 투표가 여러 Entry에 분산 → 경합 감소
- 인프라 스케일업 가능 → DB 병렬 처리 향상
- 캐시 히트율 증가 → Entry 조회 최적화
```

---

## 8. 수정 파일 목록

| 파일 | 변경 내용 |
|:-----|:---------|
| `build.gradle` | `commons-pool2` 의존성 추가 |
| `application-dev.yml` | Lettuce pool 설정 (max-active: 20) |
| `application-prod.yml` | Lettuce pool 설정 (max-active: 20) |
| `application-local.yml` | Lettuce pool 설정 (max-active: 10) |
| `RankingRedisService.java` | `incrementVoteAndGetScore()` Pipeline 메서드 추가 |
| `VoteService.java` | voteAsync()에서 Pipeline 호출로 교체 |
| `VoteConsistencyScheduler.java` | **신규 생성** - 5분 주기 정합성 검증 |
| `KakaoCommunityApplication.java` | `@EnableScheduling` 추가 |

---

## 9. 다음 단계

- [ ] Redis 중복체크 전환 (Pooling 적용으로 단일 커넥션 병목 해결됨)
  → DB 중복체크(~500ms) → Redis SISMEMBER(~5ms)로 대폭 개선 예상
- [ ] 100/200 VU 부하 테스트로 스케일링 한계 확인
- [ ] p95 < 500ms 달성 후 포트폴리오 최종 문서화
