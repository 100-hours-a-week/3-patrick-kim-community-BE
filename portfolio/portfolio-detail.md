# PetStar 포트폴리오 — 셀링포인트 딥다이브

> 반려동물 사진 콘테스트 플랫폼 PetStar의 핵심 기술적 도전 3가지를 심층 분석한 문서입니다.
> 면접에서 "왜 이 기술을 선택했는가?", "어떤 문제를 어떻게 해결했는가?"에 답할 수 있는 수준으로 작성했습니다.

---

# SP1. 동시성 문제 해결에서 비동기 투표 아키텍처까지의 진화

## 1. 배경: 왜 이 문제를 풀어야 했는가

PetStar는 반려동물 사진 콘테스트 플랫폼입니다. 주간 챌린지에 사진을 출품하고, 사용자들이 투표해서 실시간 랭킹으로 우승자를 결정합니다. 이 서비스의 특성상 **챌린지 마감 직전에 인기 사진(Entry)에 투표가 집중**되는 Hot Spot 문제가 필연적으로 발생합니다.

"챌린지 종료까지 10분!" 이라는 상황에서 수십~수백 명의 사용자가 같은 Entry에 동시에 투표를 시도합니다. 이때 발생하는 두 가지 동시성 문제를 반드시 해결해야 했습니다.

**문제 1: Check-then-Act Race Condition (중복 투표)**

```
Thread A: existsBy(entryId, memberId) → false
Thread B: existsBy(entryId, memberId) → false    ← A의 INSERT 전에 체크
Thread A: INSERT vote → 성공
Thread B: INSERT vote → 성공 (중복!)
```

중복 체크와 INSERT 사이의 시간 간격에 다른 스레드가 끼어들어 같은 사용자의 투표가 2건 저장됩니다.

**문제 2: Lost Update (투표수 누락)**

```
Thread A: SELECT vote_count → 10
Thread B: SELECT vote_count → 10
Thread A: UPDATE vote_count = 11
Thread B: UPDATE vote_count = 11    ← 10+2=12가 아닌 11
```

비원자적인 `voteCount++` 연산으로 인해 투표수가 실제보다 적게 기록됩니다. 콘테스트 결과를 결정하는 수치이므로 절대 허용할 수 없는 문제입니다.

---

## 2. 문제 분석: 3가지 동시성 제어 전략 비교

이 문제를 해결하기 위해 세 가지 전략을 직접 구현하고 50명 동시 투표 환경(k6, 같은 Entry에 동시 투표)에서 비교 테스트했습니다.

### Pessimistic Lock (비관적 락)

`SELECT ... FOR UPDATE`로 행 락을 잡고 순차 처리합니다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT e FROM Entry e WHERE e.id = :entryId")
Optional<Entry> findByIdWithPessimisticLock(@Param("entryId") Long entryId);
```

결과: **성공률 100%, p95 1.73s**. 느리지만 확실합니다. 50명이 순차적으로 Lock을 획득하고 처리하므로 마지막 사람은 49명을 기다려야 합니다.

### Optimistic Lock (낙관적 락)

`@Version` 컬럼으로 업데이트 시점에 충돌을 감지합니다. 결과: **성공률 16% (8/50)**, p95 885ms. Hot Spot에서는 충돌이 너무 잦아서 대부분의 요청이 실패합니다. 재시도를 해도 다시 충돌이 발생하는 악순환에 빠집니다.

### Atomic Update + 데드락 재시도

DB Unique 제약조건으로 중복 방지, 원자적 `vote_count = vote_count + 1` 쿼리로 Lost Update 방지. 결과: **재시도 없이 12% 성공, 지수 백오프+지터 재시도 시 94% 성공**, p95 2.98s.

두 테이블(Vote INSERT + Entry UPDATE)을 동시에 조작하면서 데드락이 발생합니다. 재시도로 복구되지만 지수 백오프 때문에 오히려 Pessimistic Lock보다 느립니다.

### 최종 선택: Pessimistic Lock

Hot Spot 상황에서는 **재시도보다 순차 처리가 더 효율적**이라는 것이 핵심 교훈이었습니다. Pessimistic Lock은 유일하게 100% 성공률을 보장하면서, 재시도 기반 전략(2.98s)보다 빠른 1.73s를 기록했습니다. 복잡한 재시도 로직도 불필요합니다.

그러나 p95 1.73s는 사용자 경험에 심각한 문제입니다. 이 지점에서 "Lock을 유지하면서 빠른 응답을 얻는 것은 구조적으로 불가능하다"는 결론에 도달했습니다. 아키텍처를 바꿔야 합니다.

---

## 3. 해결 과정: 비동기 아키텍처로의 전환

### 핵심 통찰: "사용자에게 보이는 값"과 "저장되는 값"을 분리

사용자가 투표 버튼을 누르고 기대하는 것은 "랭킹에 내 투표가 반영되었다"는 피드백입니다. DB에 Vote 레코드가 저장되었는지는 사용자와 무관합니다. 이 관찰에서 다음 아키텍처가 나왔습니다.

```
Before (동기):
  Client → API → SELECT FOR UPDATE → INSERT → COMMIT → Response (p95 1.73s)
                       ↑ Lock 대기 (병목)

After (비동기):
  Client → API → DB 중복체크 → Redis ZINCRBY → SQS 전송 → Response (p95 658ms)
                  (HikariCP 30 병렬)  (즉시 반영)    (Fire&Forget)
                                                          ↓
                                        SQS Queue → Consumer → DB 저장 (비동기)
```

투표 응답 경로에서 DB Lock을 완전히 제거했습니다. 대신 Redis Sorted Set이 실시간 랭킹을 즉시 반영하고, DB 저장은 SQS를 통해 비동기로 처리합니다.

### MQ 선택: 왜 SQS인가

메시지 큐 후보로 Kafka, RabbitMQ, SQS를 비교했습니다.

Kafka는 초당 수백만 메시지를 처리할 수 있는 분산 로그 시스템이지만, PetStar의 예상 투표량은 분당 수천 건 수준입니다. MSK(AWS Managed Kafka) 최소 비용이 월 $200 이상이고, Zookeeper/KRaft 관리, 브로커 3대 이상 운영 등 소규모 프로젝트에는 명백한 오버엔지니어링입니다.

RabbitMQ는 적절한 선택지이지만, EC2에 직접 Docker로 운영해야 하므로 관리 부담이 있습니다.

SQS Standard Queue는 완전 관리형으로 운영 부담이 0이고, 프리티어 100만 건 무료, 초과 시에도 $1/월 수준입니다. PetStar의 요구사항 — 순서 보장 불필요(투표는 교환법칙 성립), 중복 방지는 애플리케이션 레벨에서 해결, DLQ 내장 — 을 정확히 충족합니다.

**"100바이트짜리 투표 메시지에 분산 로그 시스템은 불필요하다"**는 판단으로 SQS를 선택했습니다.

### Standard Queue vs FIFO Queue

FIFO Queue가 제공하는 Exactly-once 처리와 순서 보장은 PetStar 투표에 불필요합니다.

투표는 교환법칙이 성립하는 연산입니다. User A가 먼저든 User B가 먼저든 최종 결과는 `voteCount + 2`로 동일합니다. 중복 방지는 DB Unique 제약조건이 시간 제한 없이 완전하게 보장하는 반면, FIFO의 중복 제거 윈도우는 5분으로 제한됩니다.

또한 FIFO는 MessageGroupId당 300 TPS 제한이 있어 확장 시 제약이 됩니다. Standard는 무제한입니다.

---

## 4. 구현 상세: 각 컴포넌트의 역할

### DB 중복 체크 (HikariCP 30개 병렬)

```java
if (voteRepository.existsByEntryIdAndMemberId(entryId, memberId)) {
    throw new GeneralException(ErrorStatus._ALREADY_VOTED);
}
```

왜 Redis가 아닌 DB로 중복 체크를 하는가? Phase 10-2에서 Redis SISMEMBER로 전환을 시도했지만, Lettuce의 기본 단일 커넥션 멀티플렉싱이 고동시성에서 직렬화 병목을 만들어 p95가 516ms → 1,300ms로 역전되었습니다. 반면 DB는 HikariCP 30개 커넥션이 병렬로 처리하므로 오히려 더 빠릅니다. 각 컴포넌트의 강점을 활용하는 Hybrid 접근으로 전환했습니다.

### Redis Sorted Set (실시간 랭킹)

```java
// Pipeline으로 ZINCRBY + ZSCORE를 1 RTT로 통합
List<Object> results = redisTemplate.executePipelined(new SessionCallback<>() {
    @Override
    public Object execute(RedisOperations operations) throws DataAccessException {
        operations.opsForZSet().incrementScore(key, entryId.toString(), 1);
        operations.opsForZSet().score(key, entryId.toString());
        return null;
    }
});
```

투표 시 ZINCRBY O(log N)으로 점수를 증가시키고, ZSCORE로 현재 점수를 조회합니다. 두 명령을 Pipeline으로 묶어 네트워크 왕복을 2회에서 1회로 줄였습니다.

Lettuce 커넥션 풀링(commons-pool2, max-active: 20)을 추가하여 단일 커넥션 직렬화 문제를 해결했습니다.

### SQS Fire & Forget (비동기 DB 저장)

Redis 반영 후 SQS에 투표 메시지를 전송합니다. Consumer는 `maxConcurrentMessages=1`로 순차 처리하여 같은 Entry에 대한 동시 UPDATE 데드락을 방지합니다. Consumer 레벨에서도 `existsByEntryIdAndMemberId`로 Idempotency를 보장합니다.

---

## 5. 성능 변천사와 결과

| Phase | 전략 | p95 (50 VUs Hot Spot) | 개선율 |
|:------|:-----|:---------------------|:-------|
| Phase 6 | Pessimistic Lock | 1,730ms | 기준 |
| Phase 10 | SQS 비동기 (첫 버전) | 516ms | 70% 개선 |
| Phase 10-2 | Redis 중복체크 (실패) | 1,300ms | 성능 역전 |
| Phase 10-2b | Hybrid (DB+Redis+SQS) | 688ms | 60% 개선 |
| Phase 10-3 | + Lettuce 풀링 + Pipeline | **658ms** | **62% 개선** |

최종적으로 p95를 1,730ms에서 658ms로 62% 개선했습니다. Eventual Consistency를 수용하되, 정합성 스케줄러(5분 주기)로 Redis↔DB 불일치를 자동 보정하여 최종 일관성을 보장합니다.

---

## 6. 교훈

**"재시도가 항상 답은 아니다."** Atomic + Retry 전략은 데드락 후 지수 백오프로 인해 오히려 Pessimistic Lock보다 느렸습니다. Hot Spot에서는 순차 처리가 재시도보다 효율적입니다.

**"Lock을 유지하면서 빠른 응답은 구조적으로 불가능하다."** 문제를 같은 레이어에서 풀려고 하면 한계가 있습니다. "사용자에게 보이는 값"과 "저장되는 값"을 분리하는 아키텍처 변경이 근본적 해결이었습니다.

**"각 컴포넌트의 강점을 활용하라."** DB는 병렬 중복 체크(HikariCP 30개 커넥션), Redis는 실시간 랭킹(Sorted Set), SQS는 비동기 영속화. 하나의 기술로 모든 것을 해결하려 하지 않고, 각각의 장점을 조합한 Hybrid 아키텍처가 최적해였습니다.

---
---

# SP2. 외부 서비스 장애 대응 — Graceful Degradation

## 1. 배경: 왜 장애 대응이 중요한가

비동기 투표 아키텍처(SP1)를 도입하면서 시스템이 Redis와 SQS 두 개의 외부 서비스에 의존하게 되었습니다. 성능은 크게 개선되었지만, 새로운 위험이 생겼습니다. Redis나 SQS가 장애를 일으키면 투표 전체가 중단될 수 있습니다.

콘테스트 마감 직전에 Redis 서버가 다운되었다고 가정합니다. 이 상황에서 "투표가 안 됩니다"라는 에러를 보여주는 것은 허용할 수 없습니다. **외부 서비스 장애가 전체 서비스 중단으로 이어지면 안 된다**는 원칙 아래 Graceful Degradation 전략을 설계했습니다.

---

## 2. 문제 분석: 어떤 장애 시나리오가 있는가

투표 시스템의 정상 흐름은 다음과 같습니다.

```
Client → API → DB 중복체크 → Redis(ZINCRBY) → SQS(전송) → Response
```

여기서 장애가 발생할 수 있는 지점은 네 가지입니다.

1. **SQS 네트워크 단절**: SQS 전송 실패 → DB에 영속화가 안 됨
2. **Redis 서버 다운**: Redis Pipeline 실패 → 랭킹 반영 불가, 중복 체크 불가
3. **SQS + Redis 동시 장애**: 비동기 경로 전체 사용 불가
4. **Consumer 크래시**: 메시지 수신 후 처리 중 다운

각 시나리오마다 "투표는 계속 성공하되, 성능만 저하된다"는 목표를 설정했습니다.

---

## 3. 해결 과정: 5단계 방어선 설계

### Layer 1: DB Unique 제약조건 (최종 방어선)

어떤 외부 서비스가 죽어도, DB의 `UNIQUE(entry_id, member_id)` 제약조건은 살아 있습니다. 이것이 중복 투표를 막는 최종 방어선입니다.

### Layer 2: SQS 장애 → 동기 DB 저장 Fallback

Phase 11에서 VoteProducer를 동기 전송으로 전환했습니다. 기존의 Fire & Forget 패턴은 전송 실패를 감지할 수 없는 dead code 버그가 있었습니다.

```java
try {
    voteProducer.sendVote(voteMessage);  // 동기 전송
} catch (Exception e) {
    log.warn("SQS 전송 실패, DB 직접 저장으로 전환");
    syncWriteToDb(entryId, memberId);    // DB에 직접 저장
}
```

SQS가 죽으면 API 서버가 직접 DB에 Vote를 INSERT합니다. Redis에는 이미 반영되어 있으므로 랭킹은 정상입니다.

### Layer 3: Redis 장애 → Circuit Breaker → Pessimistic Lock Fallback

Resilience4j Circuit Breaker를 적용했습니다. Redis 호출 실패율이 50%를 초과하면 Circuit Breaker가 OPEN 상태로 전환되고, 이후 요청은 자동으로 `votePessimistic()` Fallback으로 라우팅됩니다.

여기서 핵심 설계 결정이 있습니다. SP1에서 구현한 Pessimistic Lock 동기 경로를 **삭제하지 않고 Fallback 자산으로 재활용**했습니다. 비동기 아키텍처로 전환하면서 기존 동기 코드를 제거하는 것이 일반적이지만, 장애 대응 관점에서는 동기 경로가 완벽한 Fallback입니다. 이미 100% 정합성이 검증된 코드이기 때문입니다.

```
정상: Redis + SQS 비동기 (p95 138ms)
Redis 장애: Circuit Breaker OPEN → votePessimistic() (p95 1,022ms)
30초 후: HALF_OPEN → Redis 복구 확인 → CLOSED 복귀
```

Circuit Breaker 전환 속도에서 Redis `connect-timeout` 설정이 결정적이었습니다. 기본값 60초에서는 각 요청이 60초간 타임아웃을 기다려야 하므로 Circuit Breaker 전환이 늦어집니다. 이를 2초로 튜닝하여 빠른 장애 감지 → 즉시 Fallback 전환이 가능하도록 했습니다.

### Layer 4: DLQ + 재처리 스케줄러

SQS Consumer에서 3회 이상 실패한 메시지는 Dead Letter Queue로 이동합니다. DLQ 재처리 스케줄러(30분 주기)가 자동으로 메시지를 꺼내 원래 큐로 재전송합니다. DLQ의 message retention은 14일로 설정하여 충분한 복구 시간을 확보했습니다.

### Layer 5: 정합성 스케줄러 (최종 안전망)

5분마다 활성 챌린지의 모든 Entry에 대해 Redis score와 DB voteCount를 비교합니다.

- Redis > DB: SQS 처리 지연일 수 있으므로 30초 대기 후 재검증, 여전히 불일치면 DB 기준 동기화
- Redis < DB: Redis 키 손실 → 즉시 DB 기준으로 Redis 동기화

**Source of Truth는 항상 DB**입니다. Redis는 캐시 역할이므로 유실되어도 DB에서 재구축할 수 있습니다.

---

## 4. 검증: k6 장애 시나리오 테스트

단순히 코드를 작성하는 것이 아니라, 실제로 장애를 시뮬레이션하고 수치로 검증했습니다.

**테스트 환경**: EC2 t3.small, RDS db.t3.micro, 50 VUs 30초

**시뮬레이션 방법**:
- SQS 장애: `spring.cloud.aws.sqs.enabled=false`로 SQS 비활성화
- Redis 장애: 모니터링 서버에서 `docker stop petstar-redis`
- 동시 장애: 위 두 가지 동시 적용

**결과**:

| 시나리오 | 총 요청 | 성공률 | avg | p95 | Fallback 경로 |
|:---------|:--------|:------|:----|:----|:-------------|
| 정상 | 2,738 | 94.7% | 52ms | 138ms | Redis + SQS |
| SQS 장애 | 2,415 | 88.8% | 126ms | 337ms | DB 직접 저장 |
| Redis 장애 | 1,850 | 82.8% | 320ms | 1,022ms | Circuit Breaker → Pessimistic Lock |
| 동시 장애 | 1,970 | 79.7% | 269ms | 792ms | DB-only 모드 |

(실패분은 중복 투표 400 에러이며, 서버 에러가 아닙니다)

**핵심 발견**: 어떤 외부 서비스가 죽어도 투표는 계속 성공합니다. SQS 장애 시 응답은 2.4배, Redis 장애 시 6배 느려지지만 서비스는 중단되지 않습니다. "외부 서비스 죽으면 서비스도 죽는다"가 아니라 **"외부 서비스 죽으면 느려지지만 계속 동작한다"**를 수치로 증명했습니다.

---

## 5. 설계 원칙

**"DB가 Source of Truth"**: 모든 장애 복구의 기준은 DB입니다. Redis는 캐시(유실 가능, 재구축 가능), SQS는 전달 매개체(유실 시 스케줄러가 보완), DB는 최종 저장소(유실 불가)입니다.

**"Graceful Degradation > Complete Failure"**: 외부 서비스 장애 시 느려지더라도 서비스를 유지합니다. 데이터 유실보다 지연을 허용합니다. 외부 서비스 장애가 전체 서비스 중단으로 이어지면 안 됩니다.

**"Best-effort + 후속 보정"**: 실시간 처리가 실패해도 best-effort로 최선을 다하고(try-catch + 로깅), 주기적 배치로 후속 보정하며(정합성 스케줄러), 수동 개입을 최소화합니다(DLQ 자동 재처리).

---

## 6. 교훈

**"장애 대응은 설계 시점에 해야 한다."** 비동기 아키텍처를 도입할 때 "이 서비스가 죽으면?"이라는 질문을 동시에 해야 합니다. 나중에 장애 대응을 추가하려면 아키텍처를 크게 바꿔야 할 수 있습니다.

**"기존 코드는 Fallback 자산이다."** Pessimistic Lock 동기 경로를 "레거시"로 삭제하지 않고 Fallback으로 활용한 것이 핵심입니다. 이미 정합성이 검증된 코드를 재활용하는 것이 새로운 장애 대응 로직을 작성하는 것보다 안전합니다.

**"connect-timeout이 장애 감지 속도를 결정한다."** Circuit Breaker의 효과는 "얼마나 빨리 장애를 감지하느냐"에 달려 있습니다. Redis connect-timeout을 60초에서 2초로 줄인 것만으로 장애 대응 시간이 극적으로 개선되었습니다.

---
---

# SP3. 데이터 기반 성능 최적화 — 측정 → 분석 → 개선 사이클

## 1. 배경: 왜 데이터 기반이어야 하는가

성능 최적화에서 가장 위험한 것은 **"이게 병목일 것 같다"는 직감으로 최적화하는 것**입니다. 실제로 이 프로젝트에서 직감이 틀린 경우를 경험했습니다.

혼합 부하 테스트(읽기 70% + 투표 30%)에서 시스템이 100 VUs에서 이미 한계에 도달했을 때, 직감적으로 "투표의 DB 호출을 줄이면 해결될 것"이라고 판단했습니다. 하지만 실제로 투표의 DB 호출을 Redis SADD로 전환했더니 **성능이 오히려 악화**되었습니다(p95 5,439ms → 5,835ms).

데이터를 분석해보니 읽기 API가 전체 DB 호출의 78%를 차지하고 있었습니다. 투표에서 DB 호출 1회를 줄여봐야 전체에서는 미미한 영향이었습니다. 이 경험을 통해 **"측정 → 분석 → 가설 → 검증"** 사이클의 중요성을 체감했습니다.

---

## 2. 읽기 API 최적화: p95 4.76s → 130ms (97% 개선)

### 측정: k6 + Grafana로 병목 발견

k6로 읽기 API에 300 VUs 부하를 걸었을 때 p95가 4.76s로 측정되었습니다. Grafana의 HikariCP 대시보드에서 커넥션 풀이 포화(10/10)되어 있는 것을 발견했습니다.

### 분석 1: 커넥션 풀 튜닝

HikariCP max-pool-size가 10으로 설정되어 있었습니다. 300 VUs가 10개의 커넥션을 두고 경쟁하니 획득 대기 시간이 1.79s에 달했습니다.

**해결**: Pool size를 30으로 확장했습니다. 커넥션 획득 시간이 1.79s → 0.19s (89% 개선)로 감소했습니다.

### 분석 2: Pageable 적용 (81% 개선)

p6spy로 SQL을 분석하니 랭킹 조회에서 전체 Entry를 로드한 후 Java Stream으로 `limit()`하고 있었습니다. 10만 건 테이블에서 10건만 필요한데 전부 읽어오는 구조였습니다.

**해결**: DB LIMIT 절로 전환하여 100K row → 10 row만 로드하도록 변경했습니다. p95가 81% 개선되었습니다.

### 분석 3: Fetch Join (73% 개선)

p6spy에서 Entry 조회 시 Pet, Member를 개별 SELECT하는 N+1 패턴을 발견했습니다. 20건 조회 시 총 61개 쿼리가 실행되고 있었습니다.

**해결**: QueryDSL Fetch Join으로 1개 쿼리에 통합했습니다. p95가 73% 개선되었습니다.

### 분석 4: 복합 인덱스 (38% 개선)

EXPLAIN ANALYZE 결과 `Using filesort`가 발견되었습니다. 랭킹 조회에서 vote_count DESC 정렬을 위해 Full Table Scan + Filesort가 발생하고 있었습니다.

**해결**: `(challenge_id, vote_count DESC)` 복합 인덱스를 추가하여 Index Scan으로 전환했습니다. p95가 38% 개선되었습니다.

### 최종 결과

4단계 최적화를 순차 적용하여 읽기 API p95를 4.76s → 130ms (97%)로 개선했고, RPS는 32.9 → 308 (9.4배)로 증가했습니다.

---

## 3. 혼합 부하 최적화: 가설 실패에서 배운 것

### 라운드 1: 병목 발견

읽기와 투표를 동시에 실행하는 혼합 부하 테스트를 설계했습니다. 읽기 70%, 투표 30%의 비율로 VU를 10 → 100 → 300 → 500 → 800까지 단계적으로 증가시켰습니다.

결과: 100 VUs에서 이미 HikariCP Active Connections가 30/30(max)에 도달, Pending Threads 최대 170, 전체 p95 5,439ms. 쿼리 자체는 6~19ms로 빠르지만, 커넥션 획득 대기가 응답 시간의 99%를 차지했습니다.

### 라운드 2: 가설 실패 (핵심 경험)

**가설**: "투표의 DB exists 호출을 Redis SADD로 전환하면 커넥션 사용이 줄어 포화가 완화될 것"

**결과**: p95 5,439ms → 5,835ms. 오히려 7% 악화했습니다.

**데이터 분석**:
```
읽기 유저 70%: 매 반복 DB 3회 호출 → VU당 2.1회
투표 유저 30%: DB 2회 → 1회로 줄임 → VU당 0.6회 → 0.3회
전체: 3.3회 → 2.7회 (18% 감소)
```

**읽기가 전체 DB 호출의 78%를 차지**하고 있었습니다. 투표에서 1회를 줄여봐야 전체에서 0.3회(9%)에 불과합니다. 투표가 병목이라는 직감이 틀렸습니다.

**이 실패에서 얻은 핵심 데이터가 다음 라운드의 성공을 만들었습니다.**

### 라운드 3: 올바른 가설로 재도전

**새로운 가설**: "읽기 API에 Caffeine L1 캐시를 적용하면 DB 커넥션 사용을 78% 제거하여 포화가 해소될 것"

**해결**: Caffeine 로컬 캐시 (TTL 10초, max 200)를 **Controller 레벨**에 @Cacheable로 적용했습니다.

왜 Service가 아닌 Controller인가? Service 레벨에 @Cacheable을 적용하면 `@Transactional`이 먼저 시작되어 DB 커넥션을 이미 획득한 상태에서 캐시를 확인합니다. 캐시 히트여도 커넥션을 점유한 것입니다. Controller 레벨이면 캐시 히트 시 Service 레이어까지 도달하지 않으므로 **DB 커넥션을 아예 획득하지 않습니다**. 이것이 커넥션 포화를 해소하는 핵심이었습니다.

**결과**:
- 전체 p95: 5,439ms → **48ms** (99.1% 개선)
- RPS: 120.9 → **220.5** (82.4% 증가)
- 한계 VU: 100 → **800+** (8배 확장)
- 투표 p95: 4,149ms → **77ms** (98.1% 개선, 투표 코드 변경 없이!)

투표 API를 전혀 수정하지 않았는데 투표 p95가 98% 개선된 것이 가장 인상적인 결과입니다. 읽기 API가 캐시 히트로 커넥션을 사용하지 않게 되니, 투표가 즉시 커넥션을 획득할 수 있게 된 것입니다. **진짜 병목은 쿼리 속도가 아니라 커넥션 경합이었습니다.**

---

## 4. 최적화 방법론 정리

이 프로젝트를 통해 확립한 성능 최적화 사이클:

```
1. 측정 (Measure)
   - k6 부하 테스트: 실제 트래픽 패턴 시뮬레이션 (읽기 70% + 투표 30%)
   - Grafana 모니터링: HikariCP, JVM, Redis 메트릭 실시간 관찰
   - p6spy: 실행된 SQL 쿼리와 실행 시간 분석

2. 분석 (Analyze)
   - Grafana에서 커넥션 풀 포화 확인 (Active = Max)
   - EXPLAIN ANALYZE로 쿼리 실행 계획 분석
   - 트래픽 비율별 DB 호출 횟수 계산

3. 가설 (Hypothesize)
   - "투표의 DB 호출을 줄이면 개선될 것" → 검증 → 실패
   - "읽기의 DB 호출을 캐시로 제거하면 개선될 것" → 검증 → 성공

4. 검증 (Verify)
   - 동일 조건, 동일 스크립트로 Before/After 비교
   - 수치로 개선 효과 증명
```

이 사이클의 핵심은 **"가설이 틀릴 수 있다"는 전제**입니다. 데이터 없이 직감으로 최적화하면 Round 2처럼 헛수고할 수 있습니다. 측정 결과를 근거로 가설을 수정하고 재도전하는 것이 진짜 엔지니어링입니다.

---

## 5. 전체 성능 성과

| 지표 | Before | After | 개선율 |
|------|--------|-------|--------|
| 읽기 API p95 | 4.76s | 130ms | 97% 개선 |
| 투표 Hot Spot p95 | 1,730ms | 658ms | 62% 개선 |
| 혼합 부하 p95 | 5,439ms | 48ms | 99.1% 개선 |
| RPS (읽기) | 32.9 | 308 | 9.4배 증가 |
| RPS (혼합) | 120.9 | 220.5 | 82.4% 증가 |
| 동시 처리 한계 | 100명 | 800명+ | 8배 확장 |

---

## 6. 교훈

**"측정하지 않으면 최적화할 수 없다."** k6, Grafana, p6spy의 조합으로 병목을 정확히 짚어내지 못했다면, Round 2의 실패에서 헤어나오지 못했을 것입니다.

**"가설이 틀렸을 때가 가장 중요한 순간이다."** Round 2의 실패는 실패가 아닙니다. "읽기가 DB 호출의 78%를 차지한다"는 데이터를 얻은 것이 Round 3 성공의 직접적 원인입니다.

**"@Cacheable의 위치가 성능을 결정한다."** Controller 레벨과 Service 레벨에 같은 @Cacheable을 적용해도 커넥션 사용 여부가 완전히 달라집니다. 프레임워크의 동작 원리를 이해하는 것이 올바른 설계의 전제입니다.

**"진짜 병목은 예상과 다를 수 있다."** 쿼리가 6ms이어도 커넥션 대기가 12초면 응답은 12초입니다. 개별 쿼리 최적화보다 커넥션 경합 해소가 더 큰 개선을 가져올 수 있습니다.
