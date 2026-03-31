# SP1. Hot Spot 동시성 — 정합성과 성능 사이의 트레이드오프

> **핵심 질문**: "정합성을 유지하면서 어떻게 빠르게 만들 것인가?"

---

## 1. 비즈니스 문제

PetStar에서 투표 결과는 랭킹을 결정하고, 랭킹은 우승자를 결정합니다. **1표라도 누락되면 안 됩니다.**

챌린지 마감 직전, 인기 사진에 수백 명이 동시 투표합니다. 이것이 **Hot Spot** 문제입니다.

```
"챌린지 종료까지 10분!"
    ↓
인기 Entry에 투표 집중
    ↓
같은 Entry에 수백 명 동시 투표
    ↓
동시성 문제 발생
```

---

## 2. Race Condition 발견

부하 테스트에서 **투표 50번 → 결과 48개** 같은 현상이 발생했습니다. 두 가지 Race Condition을 발견했습니다.

### 2-1. Check-then-Act (중복 투표 통과)

```java
// "확인"과 "행동" 사이에 원자성이 없다
if (voteRepository.existsByEntryIdAndMemberId(entryId, memberId)) {
    throw new DuplicateVoteException();
}
// ← 이 시점에 다른 스레드가 끼어들 수 있음!
voteRepository.save(vote);
```

```
시간  Thread A                 Thread B
───────────────────────────────────────
t1    existsBy → false
t2                            existsBy → false
t3    save(vote) ✓
t4                            save(vote) ✓  ← 중복!
```

### 2-2. Lost Update (투표 수 누락)

```
시간  Thread A                 Thread B
───────────────────────────────────────
t1    READ voteCount → 10
t2                            READ voteCount → 10
t3    WRITE voteCount = 11
t4                            WRITE voteCount = 11  ← A의 +1 사라짐!

기대값: 12, 실제값: 11
```

**PetStar에서의 영향**: 1표 = 순위 변동 가능 → 절대 허용 불가

---

## 3. 락 전략 비교: 이론 vs 현실

### 3가지 전략

| 전략 | 철학 | 동작 |
|------|------|------|
| **Pessimistic Lock** | "충돌이 발생할 것이다" | `SELECT ... FOR UPDATE`로 미리 락 |
| **Optimistic Lock** | "충돌은 드물 것이다" | `@Version`으로 충돌 감지 후 재시도 |
| **Atomic Update** | DB의 원자성 활용 | `SET count = count + 1` + UK 제약 |

### Hot Spot 부하 테스트 (50명 동시 투표, 같은 Entry)

| 전략 | 성공률 | p95 | 정합성 |
|------|--------|-----|--------|
| **Pessimistic** | **100%** | 1.73s | **50/50 ✓** |
| Optimistic | 16% | 885ms | 8/50 ✗ |
| Atomic | 12% | 633ms | 6/50 ✗ |

**충격적인 결과**: Optimistic과 Atomic은 대부분 실패했습니다.

### 왜 Optimistic Lock이 Hot Spot에서 실패하는가?

```
50명 동시 요청:

Thread 1: SELECT version=1 → UPDATE WHERE version=1 → 성공!
Thread 2~50: SELECT version=1 → UPDATE WHERE version=1 → 실패!

→ 50명 중 1명만 성공
→ 재시도해도 또 경쟁 → "재시도 지옥"
```

**"충돌은 드물 것이다"라는 가정이 Hot Spot에서는 틀립니다.**

### 왜 Atomic Update에서 데드락이 발생하는가?

투표는 **두 테이블을 동시에 수정**합니다 (Vote INSERT + Entry UPDATE). 락 획득 순서가 일정하지 않으면 데드락이 발생합니다.

```
Transaction A: INSERT vote → (entry lock 대기)
Transaction B: INSERT vote → (entry lock 대기)
→ 교차 대기 → 데드락!
```

재시도 로직을 추가해도 **Pessimistic Lock(1.73s)보다 느려졌습니다(2.98s)**. 재시도가 누적되기 때문입니다.

### 선택: Pessimistic Lock

```
PetStar 투표의 특수성:
- 두 테이블 동시 수정 (Vote + Entry) → Atomic 데드락
- Hot Spot 빈번 (인기 Entry 집중) → Optimistic 재시도 폭주
- 100% 정합성 필수 (랭킹 결정) → 실패 허용 불가

→ 역설적으로 "느린" Pessimistic Lock이 가장 빠르고 안전
```

---

## 4. Pessimistic Lock의 한계 → 비동기 분리

### 문제: Lock 경합으로 p95 1.73초

Pessimistic Lock은 **정합성은 완벽**하지만, 50명이 순차 처리되므로 마지막 사람은 49명을 기다립니다.

```
Thread 1: Lock 획득 → DB 작업 → Lock 해제
Thread 2:                       Lock 획득 → DB 작업 → Lock 해제
...
Thread 50:                                                        ...기다림
```

### 핵심 통찰: "사용자가 보는 값"과 "저장되는 값"을 분리

```
관찰:
- 사용자가 보는 랭킹은 Redis에서 조회
- DB는 영속 저장소 역할
- 랭킹만 즉시 반영되면 사용자 경험 OK

질문: 꼭 투표 API에서 DB Lock을 잡아야 하나?
→ 아니다. Redis에 즉시 반영하고, DB는 비동기로 저장하면 된다.
```

### 아키텍처 재설계

```
Before: 동기 (Pessimistic Lock)
Client → API → DB(Lock 대기 1.73초) → Response
                  ↑ 병목

After: 비동기 (Redis + SQS)
Client → API → Redis ZINCRBY(즉시) → Response (50ms)
                      ↓
                    SQS → Consumer → DB (비동기)
```

### Redis Sorted Set: 실시간 랭킹에 최적화된 자료구조

**왜 Redis Sorted Set인가?**

```
랭킹 시스템 요구사항:
1. 점수 업데이트 (투표 +1)     → ZINCRBY: O(log N)
2. 상위 N개 조회 (랭킹 페이지) → ZREVRANGE: O(log N + M)
3. 특정 항목 순위 ("내 펫은?")  → ZRANK: O(log N)

비교:
- List: O(N log N) 정렬 필요
- Hash: 정렬 불가
- Sorted Set: 모든 연산 O(log N)

→ 100만 항목에서도 ~20회 탐색으로 완료
```

**투표 시 Redis + SQS 동시 처리**:

```java
public VoteResponseDto.VoteResult voteAsync(Long entryId, Integer memberId) {
    Entry entry = entryRepository.findById(entryId)
            .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

    // Redis Pipeline: SADD(중복체크) + ZINCRBY(랭킹) + ZSCORE(점수) = 1 RTT
    List<Object> results = rankingRedisService
            .recordAndIncrementPipelined(entryId, memberId, challengeId);

    Long added = (Long) results.get(0);
    if (added == 0L) {  // SADD 결과 0 = 이미 투표함
        rankingRedisService.decrementVote(challengeId, entryId);
        throw new GeneralException(ErrorStatus._BAD_REQUEST);
    }

    // SQS Fire & Forget: 비동기 DB 저장
    voteProducer.sendVote(VoteMessage.create(memberId, entryId, challengeId));

    return VoteResponseDto.VoteResult.builder()
            .entryId(entryId).voteCount(score.intValue()).build();
}
```

### 트레이드오프 결정

```
포기한 것:
- Strong Consistency (즉시 DB 반영)
- DB 반영까지 수 초 지연 가능

얻은 것:
- 투표 p95: 1.73s → 50ms (97% 개선)
- Lock 경합 완전 제거
- 트래픽 스파이크 흡수 (SQS 버퍼)

허용 가능한 이유:
- 사용자가 보는 랭킹은 Redis (즉시 반영)
- DB는 "진실의 원천"으로 백그라운드 저장
- 수 초 지연은 비즈니스적으로 무해
```

**"정합성을 포기한 게 아니라, 정합성의 위치를 바꿨다."**
- 랭킹 정합성: Redis → 즉시 반영 (사용자가 보는 값)
- 영속 정합성: DB → 비동기 반영 (시스템이 보관하는 값)

---

## 5. Redis 캐싱 전략 설계

### 캐싱 패턴 선택: Write-Through + Cache Aside 하이브리드

캐싱 전략은 데이터 흐름에 따라 크게 3가지입니다. PetStar에서는 **용도에 따라 다른 패턴을 적용**했습니다.

| 패턴 | 동작 | 장점 | 단점 |
|------|------|------|------|
| **Cache Aside** | 읽기 시 캐시 미스 → DB 조회 → 캐시 저장 | 구현 단순, 필요한 것만 캐시 | 첫 요청 느림, Stampede 위험 |
| **Write-Through** | 쓰기 시 캐시 + DB 동시 업데이트 | 항상 최신, 읽기 빠름 | 쓰기 지연 증가 |
| **Write-Behind** | 쓰기 시 캐시만 업데이트, DB는 비동기 | 쓰기 매우 빠름 | 데이터 유실 위험 |

**PetStar 적용:**

```
[랭킹 (Redis Sorted Set)] → Write-Through
  투표 발생 → Redis ZINCRBY (즉시 반영) + SQS → DB (비동기)
  → 모든 쓰기가 즉시 Redis에 반영
  → 읽기 시 항상 최신 랭킹 (Cache Aside 불필요)

[랭킹 초기화 (Cache Warming)] → Cache Aside
  첫 조회 시 Redis에 데이터 없으면 → DB에서 로드 → Redis에 저장
  → if (!rankingRedisService.hasRanking(challengeId)) initRankingToRedis()
  → 이후 요청은 Redis에서 O(log N) 조회

[읽기 API (Caffeine L1)] → Cache Aside
  @Cacheable → 캐시 히트 → 즉시 응답 (DB 커넥션 획득 안 함)
  → 캐시 미스 → DB 조회 → 캐시 저장 → TTL 10초 후 만료
```

### 캐싱 문제와 대응

#### 1. Cache Stampede (캐시 쇄도)

```
TTL 10초인 캐시가 만료되는 순간:
  → 동시에 100개 요청이 캐시 미스
  → 100개 요청이 모두 DB에 동일 쿼리 실행
  → DB 과부하 → "캐시 때문에 더 느려지는" 역설

┌──────────────┐      ┌──────────┐
│  100 요청    │──────│ 캐시 미스 │──→ DB에 100개 쿼리 동시 실행!
│  동시 도착   │      │ (TTL 만료)│
└──────────────┘      └──────────┘
```

**PetStar 대응:**

```
1. Caffeine의 내부 동기화
   → 같은 키에 대한 동시 미스 시, 1개 스레드만 로드하고 나머지는 대기
   → Spring @Cacheable + Caffeine 조합에서 자동 제공 (sync=true 없이도)

2. 짧은 TTL (10초) + 낮은 비용 쿼리
   → Stampede가 발생해도 쿼리 자체가 빠름 (Fetch Join + Index 최적화 완료)
   → 10초 주기로 최대 1회 DB 조회 → 부하 제한

3. 읽기 API는 Controller 레벨 캐싱
   → 캐시 히트 시 DB 커넥션 자체를 획득하지 않음
   → Stampede 시에도 커넥션 풀 포화가 아닌 단순 쿼리 증가
```

**수용한 트레이드오프:** 완벽한 Stampede 방지(예: Probabilistic Early Expiration)보다 단순성을 선택. 10초 TTL + Caffeine 내부 동기화로 실질적 영향 최소화.

#### 2. Hot Key 문제

```
ranking:challenge:1  ← 인기 챌린지에 모든 투표/조회 집중
                       → Redis 단일 키에 부하 집중
                       → Redis 단일 스레드 모델에서 병목 가능
```

**PetStar 대응:**

```
1. 읽기 분산: Caffeine L1 캐시
   → 랭킹 조회는 Caffeine에서 10초간 캐시
   → Redis까지 도달하는 읽기 요청 대폭 감소
   → L1(Caffeine) → L2(Redis) 2단계 캐시

2. 쓰기 최적화: Redis Pipeline
   → SADD + ZINCRBY + ZSCORE = 1 RTT
   → 3번 왕복할 것을 1번으로 → Redis 커맨드 처리량 3배 확보

3. 현재 규모에서 충분
   → EC2 단일 서버, 최대 800 VUs
   → Redis 초당 10만+ 커맨드 처리 가능
   → 수평 확장 시 Redis Cluster 샤딩 검토
```

#### 3. Cache Invalidation (캐시 무효화)

```
"컴퓨터 과학에서 어려운 두 가지: 캐시 무효화와 네이밍"

PetStar에서의 문제:
  투표 발생 → Redis Sorted Set 즉시 반영
  BUT Caffeine 캐시에는 이전 랭킹이 10초간 남아있음
  → 사용자가 투표 후 랭킹 페이지에서 변화가 안 보일 수 있음 (최대 10초)
```

**의도적 설계 결정 — @CacheEvict를 사용하지 않은 이유:**

```
@CacheEvict를 쓰면:
  투표 → 랭킹 캐시 무효화 → 다음 조회 시 DB 재조회
  → 투표 트래픽이 높을수록 캐시 히트율 급락
  → 캐시를 적용한 의미가 사라짐

TTL 10초만 사용하면:
  투표 빈도와 무관하게 캐시 히트율 유지
  → 최대 10초 stale 데이터 (비즈니스적으로 허용 가능)
  → 실시간 랭킹은 Redis Sorted Set에서 직접 제공
  → Caffeine은 "엔트리 목록, 챌린지 목록" 등 변화가 적은 데이터만 캐싱
```

#### 4. Cache Warming (콜드 스타트)

```
Redis 재시작 또는 새 챌린지 생성 시:
  → 첫 랭킹 조회에서 캐시 미스
  → DB에서 전체 Entry 로드 → Redis 초기화
  → 이후 요청부터 Redis 활용

initRankingToRedis(challengeId):
  List<Entry> entries = entryRepository.findByChallengeId(challengeId);
  for (Entry entry : entries) {
      rankingRedisService.initEntry(challengeId, entry.getId(), entry.getVoteCount());
  }
  → Lazy Warming: 첫 요청 시 초기화 (Eager 대비 불필요한 메모리 사용 방지)
```

---

## 6. SQS 큐 아키텍처 설계

### MQ 선택: 왜 SQS인가?

| 기준 | Kafka | RabbitMQ | **SQS** |
|------|-------|----------|---------|
| 처리량 | 수백만/초 | 수만/초 | 무제한 (자동 확장) |
| 운영 부담 | 높음 (Zookeeper/KRaft) | 중간 (Erlang 런타임) | **없음** (완전 관리형) |
| 비용 | $200+/월 (MSK 3대 필수) | EC2 필요 | **$1/월** |
| 순서 보장 | 파티션 내 보장 | 큐 내 보장 | Standard: 미보장 |
| 메시지 재처리 | Consumer offset으로 자유 | 불가 | DLQ로 격리 |

**투표 메시지 특성 분석:**

```
메시지 크기: ~100 bytes (memberId, entryId, challengeId, voteId)
순서 필요: 불필요 (투표는 독립적, 순서 무관)
재시도: 필요 (DB 일시 장애 시)
처리량: 분당 최대 1,000건 (SQS 무료 범위)
장애 복구: DLQ + ConsistencyScheduler로 자동 보정

→ Kafka의 분산 로그 스트리밍은 오버엔지니어링
→ RabbitMQ의 복잡한 라우팅(Exchange/Binding)은 불필요
→ SQS: 단순 전달 + DLQ + 자동 확장 = PetStar에 적합
```

### 메시지 전달 보장: at-least-once vs exactly-once

```
세 가지 전달 보장 수준:

at-most-once:  메시지 유실 가능, 중복 없음
at-least-once: 메시지 유실 없음, 중복 가능 ← SQS Standard
exactly-once:  메시지 유실 없음, 중복 없음 (구현 비용 높음)
```

**SQS Standard Queue = at-least-once를 선택한 이유:**

```
SQS FIFO (exactly-once) vs Standard (at-least-once):

| 기준 | FIFO | Standard |
|------|------|----------|
| 중복 | 없음 | 가능 |
| 처리량 | 300 TPS 제한 | 무제한 |
| 비용 | 2배 | 기본 |

PetStar 판단:
  → 중복 투표는 DB UK + exists 체크로 방어 가능 (Consumer에서 Idempotent 처리)
  → 처리량 제한(300 TPS)은 피크 트래픽에서 병목될 수 있음
  → "중복은 Consumer가 해결" 전략이 "처리량 제한" 전략보다 유리
```

### DLQ (Dead Letter Queue) 설계

```
Main Queue: petstar-votes
  → visibility timeout: 30초 (Consumer 처리 시간 보장)
  → max receive count: 3 (3번 실패 시 DLQ로 이동)
  → retention: 1일

DLQ: petstar-votes-dlq
  → retention: 14일 (디버깅 여유)
  → CloudWatch Alarm: 10개 초과 시 알림

메시지 라이프사이클:
1. Producer → Main Queue에 전송
2. Consumer가 수신 → 30초 동안 다른 Consumer에게 안 보임 (visibility timeout)
3. 처리 성공 → 자동 삭제 (ACK)
4. 처리 실패 → 예외 발생 → 30초 후 다시 visible → 재수신
5. 3번 실패 → DLQ로 이동 → 14일간 보관 → 수동 조사
```

**Poison Pill 대응:**

```
잘못된 메시지(역직렬화 실패 등)가 계속 재시도되면:
  → 정상 메시지 처리를 막는 "Poison Pill" 현상

PetStar 대응:
  try {
      message = objectMapper.readValue(payload, VoteMessage.class);
  } catch (Exception e) {
      log.error("Failed to deserialize message");
      return;  // ← ACK (삭제) — 재시도하지 않음
  }
  → 역직렬화 실패 = 메시지 자체가 잘못됨 → 재시도 무의미 → 즉시 소비
```

### Consumer 동시성 제어

```java
// SqsConfig.java
options.maxConcurrentMessages(1)  // 동시 처리 1개
options.maxMessagesPerPoll(1)     // 폴링당 1개
```

**왜 동시성을 1로 제한했는가?**

```
동시 처리 N개를 허용하면:
  Consumer A: Entry 1 투표 처리 (Vote INSERT → Entry UPDATE)
  Consumer B: Entry 1 투표 처리 (Vote INSERT → Entry UPDATE)
  → 같은 Entry에 대한 UPDATE 경합 → 데드락 가능

PetStar 투표 Consumer의 특성:
  1. 같은 Entry에 대한 투표 메시지가 연속 도착 (Hot Spot)
  2. Vote INSERT + Entry voteCount UPDATE = 두 테이블 수정
  3. 트랜잭션 내 락 순서가 일정하지 않음

해결: maxConcurrentMessages=1 (순차 처리)
  → 데드락 원천 차단
  → 처리량 제한? → SQS는 비동기 백그라운드이므로 응답 시간에 영향 없음
  → 사용자 응답은 Redis에서 이미 완료 (50ms)
  → Consumer 처리 속도는 DB 반영 속도일 뿐
```

### Backpressure 처리

```
트래픽 스파이크 시:
  → 초당 1,000 투표 발생
  → Consumer는 초당 ~100건 처리 (순차, DB 쓰기)
  → SQS에 메시지 적체 (900건/초 누적)

SQS가 자연스러운 버퍼 역할:
  → 메시지 보관 기간 1일 (86,400초)
  → 스파이크가 끝나면 Consumer가 점진적으로 처리
  → "버스트 흡수" — 사용자 응답에는 영향 없음 (Redis에서 이미 반영)

스파이크 3,000건/분 시뮬레이션:
  Consumer 처리: ~100건/분 (순차)
  적체: 2,900건/분
  해소 시간: 스파이크 종료 후 ~29분
  → 사용자 랭킹 조회: Redis에서 즉시 (영향 없음)
  → DB 반영: 최대 30분 지연 (비즈니스적으로 허용 가능)
```

### Idempotency 이중 방어

SQS Standard Queue는 **at-least-once** 전달. 같은 메시지가 2번 올 수 있습니다.

```java
@SqsListener("petstar-votes")
@Transactional
public void handleVote(String payload) {
    VoteMessage message = parse(payload);

    // 1차 방어: 애플리케이션 레벨
    if (voteRepository.existsByEntryIdAndMemberId(entryId, memberId)) {
        return;  // 이미 처리됨
    }

    // 2차 방어: DB Unique Constraint
    try {
        voteRepository.save(vote);
        entryRepository.incrementVoteCount(entryId);
    } catch (DataIntegrityViolationException e) {
        // UK 위반 = 이미 처리됨
    }
}
```

**왜 2중 방어인가?**

```
1차만 있으면 (exists 체크):
  Thread A: existsBy → false → save 시작
  Thread B: existsBy → false → save 시작
  → Race Condition으로 둘 다 통과 → 중복 저장

2차만 있으면 (UK):
  매번 save 시도 → UK 예외 → 트랜잭션 롤백 + 예외 로깅 비용
  → 중복 메시지 빈도가 높을수록 오버헤드 증가

둘 다 있으면:
  99%는 1차에서 걸러짐 (빠름, SELECT 1회)
  극히 드문 Race Condition만 2차에서 방어 (안전)
```

---

## 7. 전체 여정 요약

```
[1막] Race Condition 발견
  동시 투표 시 Lost Update + 중복 투표
  → "어떤 락 전략을 쓸 것인가?"

[2막] 3가지 전략 비교 → Pessimistic Lock 선택
  Hot Spot에서 Optimistic 재시도 폭주, Atomic 데드락
  → 역설적으로 순차 처리가 가장 빠름
  → 100% 정합성 확보, BUT p95 1.73초

[3막] 비동기 분리 → 정합성의 위치를 바꿈
  Redis Sorted Set으로 즉시 랭킹 반영
  SQS로 비동기 DB 저장
  → 투표 p95: 1.73s → 50ms (97% 개선)
  → 정합성 100% 유지 (Idempotency 이중 방어)
```

### 핵심 교훈

1. **Hot Spot에서는 비관적 락이 유리** — 재시도 지옥보다 순차 처리가 빠름
2. **동기 vs 비동기는 비즈니스 요구사항으로 판단** — "사용자가 보는 값"이 즉시 반영되면 DB 지연은 허용 가능
3. **자료구조 선택이 성능을 결정** — Redis Sorted Set O(log N) vs List O(N log N), 100만 건에서 5만 배 차이
