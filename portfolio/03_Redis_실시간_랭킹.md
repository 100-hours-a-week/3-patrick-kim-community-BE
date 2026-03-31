# Redis 실시간 랭킹 구현

## 1. 문제 인식: 왜 캐시가 필요한가?

### 최적화 후에도 남은 한계

앞선 최적화(Pageable, Fetch Join, Index)로 랭킹 API p95를 4.76s → 146ms로 개선했습니다. 하지만 구조적 한계가 남아 있습니다.

**현재 랭킹 조회 흐름**:
```
매 요청마다:
Client → API Server → MySQL (ORDER BY + JOIN) → 응답
                         ↑
                    항상 DB 조회
```

**문제 인식**:

| 구분 | 현상 | 영향 |
|------|------|------|
| **매번 DB 조회** | 랭킹 페이지 10명 방문 = DB 쿼리 10회 | DB 부하 누적 |
| **읽기/쓰기 비율** | 랭킹 조회 >> 투표 (100:1 이상) | 동일 결과를 반복 조회 |
| **스케일 한계** | 트래픽 ↑ → DB 병목 | RDS 스케일업 비용 |

### 근본적 질문: 매번 DB를 조회해야 하는가?

```
사용자 A가 랭킹 조회 → DB ORDER BY → 결과 [1위: 강아지, 2위: 고양이, 3위: 햄스터]
사용자 B가 랭킹 조회 → DB ORDER BY → 결과 [1위: 강아지, 2위: 고양이, 3위: 햄스터]
사용자 C가 랭킹 조회 → DB ORDER BY → 결과 [1위: 강아지, 2위: 고양이, 3위: 햄스터]
...

투표가 없으면 결과는 동일 → 불필요한 반복 조회
```

**캐시 도입 시**:
```
사용자 A 랭킹 조회 → 캐시 (없음) → DB 조회 → 캐시 저장 → 응답
사용자 B 랭킹 조회 → 캐시 (있음) → 캐시에서 응답 (DB 조회 없음!)
사용자 C 랭킹 조회 → 캐시 (있음) → 캐시에서 응답 (DB 조회 없음!)
```

### 추가 요구사항: 실시간 반영

단순 캐시의 문제점:
```
캐시 TTL = 5분이라면:
  t=0:  투표 (강아지 100표 → 101표)
  t=0~5분: 랭킹 조회 시 여전히 100표로 표시
  t=5분: 캐시 만료 → 새로 조회 → 101표 반영

→ 최대 5분간 오래된 정보 표시
```

**PetStar 요구사항**: 투표 결과가 **즉시** 랭킹에 반영되어야 합니다.
- 사용자가 투표 후 랭킹 페이지에서 변화를 확인하고 싶음
- 챌린지 종료 직전 실시간 경쟁 상황

---

## 2. 기술 선택: 어떤 캐시를 사용할 것인가?

### 로컬 캐시 vs 글로벌 캐시

첫 번째 의사결정: **캐시를 어디에 저장할 것인가?**

| 구분 | 로컬 캐시 (Caffeine, Guava) | 글로벌 캐시 (Redis) |
|------|---------------------------|-------------------|
| 저장 위치 | JVM 메모리 | 외부 서버 |
| 속도 | 매우 빠름 (ns) | 빠름 (ms, 네트워크 포함) |
| 서버 간 공유 | ❌ 불가 | ✅ 가능 |
| 스케일 아웃 | ❌ 동기화 문제 | ✅ 적합 |
| 운영 복잡도 | 낮음 | 중간 (별도 서버) |

**PetStar에서 글로벌 캐시를 선택한 이유**:

```
로컬 캐시 문제: 스케일 아웃 시 불일치

┌─────────────┐     ┌─────────────┐
│  Server A   │     │  Server B   │
│ Cache: 100표│     │ Cache: 100표│
└──────┬──────┘     └──────┬──────┘
       │                   │
       ↓ 투표 +1            ↓ 랭킹 조회
│ Cache: 101표│     │ Cache: 100표│ ← 불일치!
└─────────────┘     └─────────────┘
```

```
글로벌 캐시: 일관성 보장

┌─────────────┐     ┌─────────────┐
│  Server A   │     │  Server B   │
│  투표 +1    │     │  랭킹 조회  │
└──────┬──────┘     └──────┬──────┘
       │                   │
       └───────┬───────────┘
               ▼
        ┌─────────────┐
        │    Redis    │  ← 단일 진실 공급원
        │   101표     │     모든 서버가 같은 값
        └─────────────┘
```

**판단 근거**: 현재는 단일 서버지만, **스케일 아웃을 고려**하면 처음부터 글로벌 캐시가 적합합니다.

### 왜 Redis인가? (vs Memcached)

| 구분 | Memcached | Redis |
|------|-----------|-------|
| 자료구조 | Key-Value만 | 다양한 자료구조 |
| 랭킹 기능 | 직접 구현 필요 | **Sorted Set 내장** |
| 영속성 | 없음 | 선택적 (AOF, RDB) |
| 클러스터 | 클라이언트에서 | 네이티브 지원 |

**핵심**: Redis Sorted Set은 **랭킹에 최적화된 자료구조**입니다.

### Redis Sorted Set 선택 이유

Redis의 다양한 자료구조 중 왜 Sorted Set인가?

```
랭킹 시스템 요구사항:
1. 점수 업데이트: 투표 시 +1
2. 상위 N개 조회: 랭킹 페이지
3. 특정 항목 순위: "내 반려동물은 몇 위?"
```

| 자료구조 | 점수 업데이트 | 상위 N개 | 순위 조회 |
|---------|-------------|---------|----------|
| List | O(N) 탐색 필요 | O(N log N) 정렬 | O(N) |
| Hash | O(1) | 정렬 불가 | 불가 |
| **Sorted Set** | **O(log N)** | **O(log N + M)** | **O(log N)** |

**Sorted Set 내부 구조**: Skip List + Hash Table

```
┌────────────────────────────────────────────────────────┐
│  Skip List: 정렬된 순서로 빠른 탐색                     │
│                                                        │
│  Level 3:  ──────────────────────────────► [155점]     │
│  Level 2:  ────────► [10점] ─────────────► [155점]     │
│  Level 1:  [5점] → [10점] → [50점] → ... → [155점]     │
│                                                        │
│  + Hash Table: member → score 즉시 조회               │
└────────────────────────────────────────────────────────┘

→ 삽입, 삭제, 조회 모두 O(log N)
→ 100만 개 항목에서도 20회 이내 탐색
```

---

## 3. 구현

### 3-1. 키 설계

```
ranking:challenge:{challengeId}

예: ranking:challenge:1
```

- **member**: entryId (문자열)
- **score**: voteCount (숫자)

### 3-2. 핵심 연산

```java
@Service
public class RankingRedisService {

    private static final String KEY_PREFIX = "ranking:challenge:";

    // 투표 시 점수 증가 - O(log N)
    public void incrementVote(Long challengeId, Long entryId) {
        String key = KEY_PREFIX + challengeId;
        redisTemplate.opsForZSet().incrementScore(key, entryId.toString(), 1);
    }

    // 상위 N개 조회 - O(log N + M)
    public List<Long> getTopEntryIds(Long challengeId, int limit) {
        String key = KEY_PREFIX + challengeId;
        Set<Object> result = redisTemplate.opsForZSet()
            .reverseRange(key, 0, limit - 1);
        return result.stream()
            .map(obj -> Long.parseLong(obj.toString()))
            .toList();
    }
}
```

### 3-3. 투표 시 DB + Redis 동시 업데이트

```java
@Transactional
public VoteResponseDto.VoteResult votePessimistic(Long entryId, Integer memberId) {
    Entry entry = entryRepository.findByIdWithPessimisticLock(entryId)
        .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

    // ... 검증 로직 ...

    // DB 업데이트
    voteRepository.save(vote);
    entry.increaseVoteCount();

    // Redis 업데이트 (투표 즉시 반영)
    rankingRedisService.incrementVote(entry.getChallenge().getId(), entryId);

    return VoteResponseDto.VoteResult.builder()
        .entryId(entryId)
        .voteCount(entry.getVoteCount())
        .build();
}
```

### 3-4. 랭킹 조회 시 Redis 우선

```java
public List<RankingEntry> getChallengeRanking(Long challengeId, int limit) {
    // Redis에 데이터 없으면 DB에서 초기화 (Cache Warming)
    if (!rankingRedisService.hasRanking(challengeId)) {
        initRankingToRedis(challengeId);
    }

    // Redis에서 상위 N개 entryId 조회
    List<Long> topEntryIds = rankingRedisService.getTopEntryIds(challengeId, limit);

    // DB에서 Entry 상세 정보 조회 (Fetch Join)
    List<Entry> entries = entryRepository.findEntriesByIdsWithFetchJoin(topEntryIds);

    // Redis 순서대로 정렬 및 반환
    return topEntryIds.stream()
        .map(id -> entryMap.get(id))
        .map(entry -> {
            int voteCount = rankingRedisService.getVoteCount(challengeId, entry.getId());
            return toRankingEntry(entry, voteCount);
        })
        .toList();
}
```

---

## 4. 아키텍처

### 데이터 흐름

```
┌──────────────────────────────────────────────────────────────┐
│                        투표 흐름                              │
│                                                              │
│  Client                                                      │
│    │                                                         │
│    ▼                                                         │
│  POST /entries/{id}/votes                                    │
│    │                                                         │
│    ├──────────────────────┬─────────────────────────────────│
│    │                      │                                  │
│    ▼                      ▼                                  │
│  ┌────────────┐     ┌────────────┐                          │
│  │   MySQL    │     │   Redis    │                          │
│  │ vote INSERT│     │  ZINCRBY   │                          │
│  │ entry ++   │     │  +1 점수   │                          │
│  └────────────┘     └────────────┘                          │
│                                                              │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                      랭킹 조회 흐름                           │
│                                                              │
│  Client                                                      │
│    │                                                         │
│    ▼                                                         │
│  GET /challenges/{id}/ranking                                │
│    │                                                         │
│    ▼                                                         │
│  ┌────────────┐     ┌────────────┐                          │
│  │   Redis    │────▶│ Top N IDs  │                          │
│  │ ZREVRANGE  │     │ [99991,    │                          │
│  └────────────┘     │  99961,...]│                          │
│                     └─────┬──────┘                          │
│                           │                                  │
│                           ▼                                  │
│                     ┌────────────┐                          │
│                     │   MySQL    │                          │
│                     │ Entry 상세 │                          │
│                     │ (Fetch Join)│                          │
│                     └────────────┘                          │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### Cache Warming 전략

```java
// Redis에 데이터 없을 때 DB에서 초기화
private void initRankingToRedis(Long challengeId) {
    log.info("[Redis] Initializing ranking for challengeId={}", challengeId);

    List<Entry> allEntries = entryRepository.findByChallengeId(challengeId);

    for (Entry entry : allEntries) {
        rankingRedisService.initEntry(
            challengeId,
            entry.getId(),
            entry.getVoteCount()
        );
    }

    log.info("[Redis] Initialized {} entries", allEntries.size());
}
```

---

## 5. 아키텍처 결정: Source of Truth는 어디인가?

### 핵심 질문: DB와 Redis 중 누가 "진짜"인가?

캐시를 도입하면 **두 곳에 같은 데이터**가 존재합니다. 둘이 다르면 어느 쪽이 맞는가?

```
두 가지 아키텍처 선택지:

1. DB가 Source of Truth (DB 먼저)
   투표 → DB 저장 → Redis 업데이트
   DB가 정답, Redis는 캐시

2. Redis가 Source of Truth (Redis 먼저)
   투표 → Redis 업데이트 → (비동기) DB 저장
   Redis가 정답, DB는 백업
```

### 아키텍처 1: DB가 Source of Truth (현재 선택)

```
┌────────────────────────────────────────────────────────────┐
│  투표 흐름                                                  │
│                                                            │
│  1. @Transactional 시작                                    │
│  2. Vote INSERT (DB)                                       │
│  3. Entry voteCount UPDATE (DB)                            │
│  4. 트랜잭션 커밋                                          │
│  5. Redis ZINCRBY (트랜잭션 밖)                            │
│                                                            │
│  DB 성공 → Redis 성공: 정상                                │
│  DB 성공 → Redis 실패: 다음 조회 시 Cache Warming 복구     │
│  DB 실패: 전체 롤백 (Redis 업데이트 안 함)                 │
└────────────────────────────────────────────────────────────┘
```

**장점**:
- DB 트랜잭션으로 **정합성 보장**
- Redis 장애 시에도 **DB에서 복구 가능**
- 구현이 단순

**단점**:
- DB 트랜잭션 + Redis 호출 = 약간의 지연
- DB가 병목이 될 수 있음

### 아키텍처 2: Redis가 Source of Truth (대안)

```
┌────────────────────────────────────────────────────────────┐
│  투표 흐름                                                  │
│                                                            │
│  1. Redis ZINCRBY (1~2ms, 즉시 응답)                       │
│  2. Kafka/RabbitMQ로 이벤트 발행                           │
│  3. Consumer가 비동기로 DB 저장                            │
│                                                            │
│  Redis 성공 → MQ 발행 → DB 저장 (나중에)                  │
│  Redis 실패: 전체 실패                                     │
│  MQ 실패: 재시도 큐, Dead Letter Queue                    │
└────────────────────────────────────────────────────────────┘
```

**장점**:
- **초고속 응답** (Redis만 거치면 완료)
- 높은 처리량 (DB 병목 제거)

**단점**:
- **Eventual Consistency** (DB 반영까지 지연)
- MQ 인프라 필요 (복잡도 증가)
- Redis 장애 시 데이터 유실 위험

### PetStar에서의 선택: DB가 Source of Truth

**판단 근거**:

| 기준 | 분석 | 결정 |
|------|------|------|
| **데이터 중요도** | 투표 = 랭킹 = 우승자 결정 | 정합성 최우선 |
| **현재 트래픽** | 소규모 (동시 200명) | DB 병목 없음 |
| **인프라 복잡도** | MQ 없이 단순하게 | 현재 단계에 적합 |
| **장애 복구** | DB에서 언제든 복구 가능 | 안정성 확보 |

**확장 시나리오**:
```
현재 (Phase 8):
  동시 200명 → DB가 Source of Truth로 충분

미래 (트래픽 10배 증가 시):
  → Redis Source of Truth + Kafka 비동기 동기화 검토
  → 단, Eventual Consistency 허용 가능한지 비즈니스 판단 필요
```

---

## 6. 성과

### 동작 검증

```bash
# Before: voteCount = 154
$ curl -X POST ".../entries/99991/votes/test?memberId=7777"
{"voteCount": 155}

# Redis 확인
$ redis-cli ZSCORE "ranking:challenge:1" "99991"
"155"

# 랭킹 조회
$ curl ".../challenges/1/ranking?limit=1"
{"entryId": 99991, "voteCount": 155}
```

### 기대 효과

| 지표 | 기존 (DB만) | Redis 적용 |
|------|------------|-----------|
| 랭킹 조회 | ORDER BY 쿼리 | Redis ZREVRANGE |
| 투표 반영 | 다음 조회 시 | 즉시 |
| DB 부하 | 매 요청 | Entry 상세만 |

---

## 7. 향후 개선 방향

### Phase 10: MQ 도입 시

```
┌────────────────────────────────────────────────────────────┐
│  더 높은 처리량이 필요하다면                               │
│                                                            │
│  Client                                                    │
│    │                                                       │
│    ▼                                                       │
│  POST /votes ──────▶ Redis (즉시)                         │
│                        │                                   │
│                        ▼                                   │
│                      Kafka ──────▶ Consumer ──────▶ MySQL  │
│                     (비동기)                               │
│                                                            │
│  장점: 초고속 응답, 높은 throughput                        │
│  단점: Eventual Consistency 허용 필요                      │
└────────────────────────────────────────────────────────────┘
```

### Redis Cluster

```
트래픽이 더 증가하면:
- Redis Sentinel (고가용성)
- Redis Cluster (샤딩)
- ElastiCache (AWS 관리형)
```

---

## 8. 핵심 교훈

### 1. 캐시 도입 전 질문: "정말 필요한가?"

```
캐시 도입 판단 기준:

1. 읽기/쓰기 비율
   - 읽기 >> 쓰기 → 캐시 효과 큼
   - 읽기 ≈ 쓰기 → 캐시 무효화 빈번 → 효과 낮음

2. 데이터 일관성 요구
   - 실시간 정합성 필수 → 캐시 전략 복잡
   - 약간의 지연 허용 → 단순 TTL 캐시 가능

3. 현재 병목
   - DB가 병목인가? → 캐시 효과 큼
   - 네트워크, 애플리케이션 병목 → 캐시 효과 낮음

PetStar:
  - 랭킹 조회 >> 투표 (100:1 이상) → ✓
  - 실시간 반영 필요 → 단순 TTL 불가, Write-Through 필요
  - DB ORDER BY가 병목 → ✓
  → 캐시 도입 타당
```

### 2. 자료구조 선택이 성능을 결정한다

```
Redis의 강점은 "Key-Value 스토어"가 아니라 "자료구조 서버"

랭킹 시스템에서:
  - List로 구현: O(N log N) 정렬 필요
  - Hash로 구현: 정렬 불가
  - Sorted Set: O(log N) 모든 연산

100만 개 데이터에서:
  - List: 1,000,000 * log(1,000,000) ≈ 2천만 연산
  - Sorted Set: log(1,000,000) ≈ 20 연산

→ 자료구조 하나로 1,000,000배 차이
```

### 3. Cache Warming: "캐시 미스 ≠ 장애"

```
잘못된 설계:
  캐시 미스 → 에러 → 서비스 장애

올바른 설계:
  캐시 미스 → DB에서 로드 → 캐시 저장 → 정상 응답
  (첫 요청만 느리고, 이후 요청은 캐시 히트)

PetStar 구현:
  if (!rankingRedisService.hasRanking(challengeId)) {
      initRankingToRedis(challengeId);  // DB에서 캐시 초기화
  }
  return rankingRedisService.getTopEntryIds(challengeId, limit);

→ Redis 재시작 후에도 자동 복구
→ 새 챌린지 생성 시에도 자연스럽게 캐시 빌드업
```
