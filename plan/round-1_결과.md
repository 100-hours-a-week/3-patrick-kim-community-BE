# Round 1 결과: 혼합 부하 한계 탐색

> **질문: "읽기와 쓰기가 동시에 발생하는 실제 트래픽에서, 우리 시스템은 동시 사용자 몇 명까지 버티는가?"**

---

## 1. 테스트 환경

### 인프라

| 구성 요소 | 스펙 | 비고 |
|-----------|------|------|
| App Server | EC2 t3.small (2 vCPU, 2GB) | Docker 컨테이너 |
| DB | RDS db.t3.micro (2 vCPU, 1GB) | MySQL 8.0 |
| Redis | EC2 t3.small (Docker) | Redis 7 Alpine |
| SQS | petstar-votes 큐 | 비동기 투표 저장 |

### 애플리케이션 설정

| 설정 | 값 |
|------|-----|
| HikariCP max-pool-size | 30 |
| HikariCP min-idle | 10 |
| Lettuce max-active | 20 |
| Spring Profile | dev |
| JVM | Java 21 (기본 메모리) |

### 테스트 데이터

| 데이터 | 건수 |
|--------|------|
| Member | 10,000 |
| Pet | 15,000 |
| Challenge | 30 (모두 ACTIVE) |
| Entry | 100,020 |
| Vote | 0 (테스트 중 생성) |

---

## 2. 테스트 설계

### k6 스크립트: `k6/03_mixed-load-test.js`

**사용자 행동 모델:**
- 구경 유저 (70%): 챌린지 목록 → 랭킹 확인 → 엔트리 탐색
- 투표 유저 (30%): 랭킹 확인 → 투표 → 랭킹 재확인

**투표 분포 (Hot Spot):**
- 상위 5개 Entry에 투표의 50% 집중 (인기 사진 시뮬레이션)
- 나머지 Entry에 50% 분산

**단계별 부하:**

| 단계 | VUs | 시간 | 시작 시점 |
|------|-----|------|-----------|
| Warmup | 10 | 30초 | 0s |
| Normal | 100 | 2분 | 30s |
| Peak | 300 | 2분 | 2m30s |
| Stress | 500 | 2분 | 4m30s |
| Spike | 800 | 1분 | 6m30s |
| Recovery | 300 | 1분 | 7m30s |

**총 테스트 시간:** 8분 36초

**API 엔드포인트:**
- `GET /api/v1/challenges` — 챌린지 목록
- `GET /api/v1/challenges/{id}/ranking?limit=10` — 실시간 랭킹
- `GET /api/v1/challenges/{id}/entries?limit=10` — 엔트리 목록
- `POST /api/v1/entries/{id}/votes/test?memberId={}&strategy=async` — 비동기 투표

---

## 3. 측정 결과

### 3-1. 전체 요약 (Baseline)

> 실행 시각: 2026-03-13 18:50 KST (09:50 UTC)

| 지표 | 값 | Threshold | 판정 |
|------|-----|-----------|------|
| 전체 p95 | **5,439ms** | < 1,000ms | **FAIL** |
| 에러율 | 0.42% | < 5% | PASS |
| HTTP RPS | 120.9 req/s | - | - |
| 총 요청 수 | ~62,000 | - | - |
| 총 반복 수 | 20,808 | - | - |

### 3-2. API별 응답 시간 (p95)

| API | p95 | Threshold | 판정 |
|-----|-----|-----------|------|
| 챌린지 목록 | **5,426ms** | < 500ms | FAIL |
| 랭킹 조회 | **4,708ms** | < 500ms | FAIL |
| 엔트리 목록 | **7,002ms** | < 500ms | **FAIL (최악)** |
| 투표 | **4,149ms** | < 1,000ms | FAIL |

### 3-3. 투표 결과

| 항목 | 값 |
|------|-----|
| 투표 성공 (201) | 5,976 |
| 중복 투표 (400) | 377 |
| 서버 에러 (5xx) | 0 |

### 3-4. 인프라 메트릭 (Prometheus)

#### HikariCP 커넥션 풀 (핵심 병목)

| 지표 | Max | Avg |
|------|-----|-----|
| Active Connections | **30 (= max pool)** | 16.2 |
| Pending Threads | **170** | 56.4 |
| Acquire Time | **12,419ms** | 5,445ms |

#### HikariCP Active Connections 타임라인

```
시각(UTC)  Active  해석
09:50:00   0       테스트 전
09:51:30   1       Warmup (10 VUs)
09:52:00   2       Warmup
09:53:00   6       Normal 시작 (100 VUs)
09:53:30   30      ★ 풀 포화 시작 (100 VUs 단계에서 이미!)
09:54:00   30      Peak 시작 (300 VUs)
09:54:30   30      (이후 테스트 종료까지 30 유지)
...
09:59:00   30      Spike + Recovery
09:59:30   1       테스트 종료
```

#### HikariCP Pending Threads 타임라인

```
시각(UTC)  Pending  해석
09:53:30   0        풀 포화 직후 — 아직 대기 없음
09:54:00   14       Peak (300 VUs) — 대기 시작
09:54:30   61       대기 급증
09:55:00   68
09:55:30   170      ★ Stress (500 VUs) — 최대 대기
09:56:00   170      (이후 Spike까지 170 유지)
09:58:30   167
09:59:00   51       Recovery — 감소
09:59:30   0        테스트 종료
```

#### JVM

| 지표 | Max | Avg |
|------|-----|-----|
| Live Threads | 257 | - |
| Heap Used | 434MB | 266MB |

---

## 4. 병목 분석

### 4-1. 핵심 병목: HikariCP 커넥션 풀 포화

**증거:**
1. Active Connections = 30 (max pool) → **100 VUs 단계에서 이미 포화**
2. Pending Threads 최대 170 → 170개 스레드가 커넥션 대기
3. Acquire Time 최대 12.4초 → 커넥션 획득에만 12초 소요
4. 모든 API의 p95가 4~7초 → 커넥션 대기 시간과 일치

**메커니즘:**
```
[요청 흐름]
HTTP 요청 → Tomcat 스레드 → HikariCP 커넥션 요청
                                  ↓
                    Active = 30 (모두 사용 중)
                                  ↓
                    Pending 큐에서 대기 (최대 12.4초)
                                  ↓
                    커넥션 획득 후 쿼리 실행 (수ms)
                                  ↓
                    응답 반환 (total = 대기시간 + 쿼리시간)

→ 쿼리 자체는 빠르지만 (6~19ms, p6spy 로그)
→ 커넥션 대기 시간이 응답 시간의 99%를 차지
```

### 4-2. 왜 100 VUs에서 이미 포화되는가?

**투표 Hot Path (VoteService.voteAsync)의 DB 호출 2회:**
```
① DB findById       — 커넥션 1번째 사용
② DB exists          — 커넥션 2번째 사용 (중복 투표 체크)
③ Redis Pipeline     — 커넥션 미사용
④ SQS Fire & Forget  — 커넥션 미사용
```

**읽기 API도 각각 DB 커넥션 사용:**
- 챌린지 목록: 1회
- 랭킹 조회: 1~2회 (Fetch Join)
- 엔트리 목록: 1~2회 (Fetch Join)

**30개 커넥션으로 100 VUs를 감당하려면:**
- 각 커넥션이 ~10ms 이내에 반환되어야 함
- 실제로는 투표가 2번 DB 호출 + think time으로 더 오래 점유
- 구경 유저 70%가 3개 API를 연속 호출 → 커넥션 경합 극심

### 4-3. 악화 요인: VoteConsistencyScheduler

```
[5분마다 실행]
30개 챌린지 × SELECT 3,334행 = 100,020행 풀스캔
각 쿼리 6~19ms, 총 ~3.7초간 커넥션 점유

→ 부하 테스트 중에 이 스케줄러가 실행되면
→ 1개 커넥션이 3.7초간 점유됨
→ 29개로 800 VUs를 감당해야 함
```

### 4-4. 다른 병목은 없는가?

| 후보 | 상태 | 근거 |
|------|------|------|
| DB CPU/IO 포화 | **아님** | 쿼리 실행 시간 6~19ms로 빠름 |
| Redis 병목 | **아님** | Redis 명령은 커넥션 풀 밖에서 동작 |
| App CPU 포화 | **확인 필요** | JVM 스레드 257로 높지만, 대부분 I/O 대기 |
| SQS 지연 | **아님** | Fire & Forget, 응답 시간에 영향 없음 |

**결론: 단일 병목 = HikariCP 커넥션 포화**

---

## 5. 가설 수립

### 가설 1 (Round 2 실험 A): DB exists 제거 → Redis SADD

> "투표의 DB 중복 체크(exists)를 Redis SADD로 대체하면, DB 커넥션 사용을 투표당 2회→1회로 줄여 커넥션 포화 시점이 늦춰지고, 전체 p95가 50% 이상 개선될 것이다."

**근거:**
- 투표가 DB를 2번 호출 → 1번으로 줄이면 커넥션 점유 시간 ~50% 감소
- Phase 10-3에서 Lettuce 커넥션 풀링(max-active: 20) 이미 추가됨
- `recordAndIncrementPipelined()` 메서드 이미 구현되어 있음 (미사용)
- Phase 10-2에서 Lettuce 단일 커넥션으로 실패했으나, 풀링 위에서 재시도

**예상 결과:**
- 투표 p95: 4,149ms → < 2,000ms
- 읽기 API p95도 개선 (커넥션 반환 빨라짐)
- 커넥션 포화 시점: 100 VUs → 200~300 VUs

### 가설 2 (Round 2 실험 B): DB findById도 제거 → Redis Hash 캐시

> "Entry 메타데이터를 Redis에 캐시하면 투표 Hot Path에서 DB 호출을 완전 제거하여, p95 < 500ms를 달성할 수 있다."

**전제:** 가설 1 성공 후, DB findById가 새로운 병목이 된 경우

### 가설 3: VoteConsistencyScheduler 최적화

> "스케줄러의 100K 풀스캔을 제거하거나 부하 테스트 중 비활성화하면 커넥션 1개를 절약한다."

**우선순위:** 가설 1 > 가설 3 > 가설 2

---

## 6. 다음 단계 (Round 2)

1. **실험 A 실행**: `VoteService.voteAsync()`에서 DB exists → Redis SADD 전환
   - `recordAndIncrementPipelined()` 메서드 활용
   - Pipeline: SADD(중복체크) + ZINCRBY(랭킹) + ZSCORE(현재점수) = 1 RTT
2. **동일 조건으로 재측정** (k6 03_mixed-load-test.js)
3. **Before/After 비교**
4. **결과에 따라 실험 B 또는 C 결정**

---

## 7. 포트폴리오 서술 포인트

```
[셀링포인트 2: 혼합 부하 한계 탐색]

"읽기 300 VUs는 p95 178ms로 문제없었지만,
읽기+쓰기 혼합 시 100 VUs에서 이미 커넥션 풀이 포화되었다.

→ 투표 API가 DB를 2번 호출하여 커넥션을 오래 점유
→ 30개 커넥션으로 100명도 감당 못하는 구조적 문제
→ p95 5.4초, 커넥션 대기 최대 12.4초

이것을 발견하고 → 가설(DB 호출 줄이기) → 검증의 과정으로 해결"
```
