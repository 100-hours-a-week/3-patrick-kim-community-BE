# Round 2 결과: 투표 Hot Path 최적화

> **질문: "투표 API에서 DB 호출을 줄이면 혼합 부하 성능이 개선되는가?"**
> **답: 아니다. 병목은 투표가 아니라 읽기 API였다.**

---

## 1. 실험 A: DB exists → Redis SADD 전환

### 변경 내용

```java
// Before (Phase 10-3): DB 2회 + Redis Pipeline 1회
① DB findById          — Entry 존재 확인 + challengeId
② DB exists            — 중복 투표 체크 (HikariCP)
③ Redis Pipeline       — ZINCRBY + ZSCORE (1 RTT)
④ SQS Fire & Forget    — 비동기 전송

// After (Round 2A): DB 1회 + Redis Pipeline 1회
① DB findById          — Entry 존재 확인 + challengeId (유지)
② Redis Pipeline       — SADD + ZINCRBY + ZSCORE (1 RTT, 중복체크 포함)
③ SQS Fire & Forget    — 비동기 전송
→ DB 호출 2회 → 1회로 감소
```

### 코드 변경

- `VoteService.voteAsync()`: DB `existsByEntryIdAndMemberId` 제거
- `recordAndIncrementPipelined()` 사용 (SADD + ZINCRBY + ZSCORE = 1 RTT)
- SADD 결과가 0(중복)이면 ZINCRBY 롤백 (`decrementVote`)

### 테스트 조건

Round 1과 **완전히 동일한 조건**:
- 동일 k6 스크립트 (`03_mixed-load-test.js`)
- 동일 인프라 (t3.small + db.t3.micro)
- 동일 데이터 (10K Members, 100K Entries, 30 Challenges)
- 동일 VU 단계 (10 → 100 → 300 → 500 → 800 → 300)

---

## 2. 측정 결과

### 2-1. Before/After 비교

| 지표 | Round 1 (Before) | Round 2A (After) | 변화 |
|------|------------------|-------------------|------|
| **전체 p95** | 5,439ms | 5,835ms | **+7% (악화)** |
| 챌린지 목록 p95 | 5,426ms | 5,889ms | +9% |
| 랭킹 p95 | 4,708ms | 4,869ms | +3% |
| 엔트리 p95 | 7,002ms | 7,595ms | +8% |
| 투표 p95 | 4,149ms | 4,257ms | +3% |
| 에러율 | 0.42% | 0.73% | +0.31%p |
| HTTP RPS | 120.9 | 119.2 | -1.4% |
| 투표 성공 | 5,976 | 5,995 | +0.3% |
| 투표 중복 | 377 | 93 | -75% |

### 2-2. 인프라 메트릭 비교

| 지표 | Round 1 | Round 2A | 변화 |
|------|---------|----------|------|
| HikariCP Active Max | 30 | 30 | 동일 (풀 포화) |
| HikariCP Pending Max | 170 | 170 | 동일 |
| HikariCP Acquire Max | 12,419ms | **15,154ms** | **+22% 악화** |
| HikariCP Acquire Avg | 5,445ms | 6,850ms | +26% 악화 |

---

## 3. 실패 원인 분석

### 3-1. 왜 개선이 없는가?

**가설이 틀렸다.** "투표의 DB 호출을 줄이면 커넥션 포화가 완화된다"는 가설의 전제가 잘못되었다.

```
[트래픽 비율]
읽기 유저 70%: 챌린지목록 + 랭킹 + 엔트리 = 3개 API, 각각 DB 1회 = 3회
투표 유저 30%: 랭킹 + 투표 + 랭킹 = DB 2회 (투표 1회 + 랭킹 2회)
                                    → Round 2A: DB 2회 (투표 0회 + 랭킹 2회)

[VU당 평균 DB 호출 수]
Before: 0.7 * 3 + 0.3 * (2 + 2) = 2.1 + 1.2 = 3.3회
After:  0.7 * 3 + 0.3 * (0 + 2) = 2.1 + 0.6 = 2.7회

→ DB 호출 18% 감소
→ 하지만 읽기가 전체 DB 호출의 78% (2.1/2.7)를 차지
→ 투표의 DB 1회 제거는 전체에서 미미한 영향
```

### 3-2. 진짜 병목은 읽기 API

```
[커넥션 사용 패턴]
읽기 API: 커넥션 획득 → 쿼리 실행 (6~19ms) → 결과 매핑 → 커넥션 반환
         → 커넥션 점유 시간: ~20-50ms (빠르지만 요청이 많음)

투표 API: 커넥션 획득 → findById (6ms) → 커넥션 반환
         → Round 2A에서는 1회만 사용 → 점유 시간 ~10ms

문제: 30개 커넥션에 100+ VU가 동시 요청
     → 읽기 70%가 매 반복 3회씩 커넥션 사용
     → 투표에서 1회 줄여봐야 전체 경합에 미미한 영향
```

### 3-3. Phase 10-2 실패와의 비교

```
[Phase 10-2]: Redis SISMEMBER로 전환 → p95 1,300ms 회귀
  원인: Lettuce 단일 커넥션 병목

[Round 2A]: Redis SADD Pipeline 전환 → p95 변화 없음
  원인: Lettuce 풀링으로 Redis 병목은 해소됨
       BUT 읽기 API가 커넥션 풀을 지배하므로 투표 최적화 효과 없음

→ Phase 10-3에서 추가한 Lettuce 풀링은 Redis 병목을 해소하는 데 성공했다.
→ 하지만 진짜 병목은 Redis가 아니라 HikariCP + 읽기 API 조합이었다.
```

### 3-4. 투표 중복 감소의 의미

투표 중복이 377 → 93으로 크게 줄었다. 이는 Redis SADD가 DB exists보다 **더 빠르게** 중복을 차단하기 때문이다. DB exists는 커넥션 대기 시간이 포함되어 있어 중복 투표가 타이밍 차이로 통과되는 경우가 있었지만, Redis SADD는 즉시 차단한다.

→ **정합성 측면에서는 Redis SADD가 더 우수하다.**

---

## 4. 새로운 가설

### 가설: 읽기 API 캐싱으로 커넥션 사용 제거

> "읽기 API(챌린지 목록, 랭킹, 엔트리)에 캐시를 적용하면,
> DB 커넥션 사용을 대폭 줄여 커넥션 포화 시점이 크게 늦춰지고,
> 전체 p95가 1초 이내로 개선될 것이다."

**근거:**
- 읽기가 전체 DB 호출의 78%를 차지
- 챌린지 목록: 변경 빈도 낮음 → 10초 TTL 캐시 가능
- 랭킹: Redis Sorted Set에서 이미 서빙 가능 (DB 불필요)
- 엔트리: 변경 빈도 낮음 → 5~10초 TTL 캐시 가능

**예상 효과:**
- 읽기 API의 DB 호출 90%+ 제거 (캐시 히트)
- 30개 커넥션이 주로 투표 + 캐시 미스에만 사용
- 커넥션 포화 시점: 100 VUs → 500+ VUs

**구현 방안:**
1. **Caffeine 로컬 캐시** (L1): 챌린지 목록, 엔트리 목록
   - TTL 10초, 최대 100개
   - 네트워크 비용 0, 가장 빠름
2. **Redis 캐시** (L2): 랭킹 조회
   - 이미 Redis Sorted Set에 데이터 있음
   - DB 폴백 대신 Redis에서 직접 서빙

---

## 5. 포트폴리오 서술 포인트

```
[3막 구조 — 수정]

1막 (Phase 10-2): Redis 전환 시도 → 실패 (Lettuce 단일 커넥션 병목)
2막 (Phase 10-3): Lettuce 풀링 보강 → Redis 병목 해소
3막 (Round 2A): 투표 DB 제거 재시도 → 실패 (읽기가 진짜 병목)
4막 (Round 2B): 읽기 캐싱 → ???

핵심 메시지:
"가설이 틀릴 수 있다. 투표가 병목이라고 생각했지만,
데이터는 읽기가 커넥션의 78%를 차지한다고 말했다.
측정 결과를 근거로 가설을 수정하고 재도전하는 것이 진짜 엔지니어링."
```

---

## 6. 부하 테스트 이력

| 날짜 | 테스트 | 전체 p95 | 에러율 | 비고 |
|------|--------|----------|--------|------|
| 2026-03-13 18:50 | Round 1 Baseline | 5,439ms | 0.42% | DB 2회 (기존) |
| 2026-03-13 19:33 | Round 2A (DB exists→Redis SADD) | 5,835ms | 0.73% | DB 1회 (개선 없음) |
