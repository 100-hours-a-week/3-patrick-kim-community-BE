# Round 1: 혼합 부하 한계 탐색

> **질문: "읽기와 쓰기가 동시에 발생하는 실제 트래픽에서, 우리 시스템은 동시 사용자 몇 명까지 버티는가?"**

---

## 1. 왜 이 질문인가

지금까지의 모든 테스트는 **읽기만** 또는 **쓰기만** 따로 측정했다.

```
Phase 9 결과:  읽기 300 VUs → p95 178ms, 0% 에러     ✅
Phase 10 결과: 쓰기 50 VUs (Hot Spot) → p95 658ms    ✅

하지만 실제 서비스에서는?
→ 랭킹 보면서 투표하고, 투표하면서 랭킹 새로고침한다
→ 읽기와 쓰기가 같은 HikariCP 30 커넥션을 공유한다
→ 동시에 돌리면 어떻게 되는지 아직 모른다
```

이것은 PetStar 도메인에서 자연스러운 질문이다:
- 콘테스트 진행 중 = 구경(읽기) + 투표(쓰기) 동시 발생
- 마감 직전 = 양쪽 다 폭주

---

## 2. 실험 설계

### 2-1. 실제 사용자 행동 모델링

PetStar에서 사용자가 하는 행동을 비율로 모델링한다.

```
[PetStar 사용자 행동 패턴]

구경 유저 (70%): 챌린지 목록 → 랭킹 확인 → 엔트리 탐색
투표 유저 (25%): 랭킹 확인 → 마음에 드는 엔트리에 투표 → 랭킹 재확인
적극 유저 (5%):  투표 + 응원 메시지 작성

→ API 호출 비율:
  GET /challenges             15%
  GET /challenges/{id}/ranking  35%
  GET /challenges/{id}/entries  20%
  POST /entries/{id}/votes/test 25%  (투표)
  POST /entries/{id}/supports    5%  (응원 메시지)
```

### 2-2. k6 시나리오: 혼합 부하 테스트

```javascript
// k6/03_mixed-load-test.js

// 핵심: 읽기와 쓰기를 섞어서 실제 트래픽 패턴 재현

시나리오:
  warmup:      10 VUs,  30초   (워밍업)
  normal:     100 VUs,   2분   (MAU ~2만 평시)
  peak:       300 VUs,   2분   (MAU ~6만 피크)
  stress:     500 VUs,   2분   (MAU ~10만 피크)
  spike:      800 VUs,   1분   (마감 직전 스파이크)
  recovery:   300 VUs,   1분   (스파이크 후 회복)

각 VU는 매 반복마다:
  1. 랜덤으로 행동 유형 선택 (구경/투표/적극)
  2. 해당 행동 패턴대로 API 호출
  3. think time: 1~3초 (사람처럼)
```

### 2-3. Hot Spot 혼합 시나리오

인기 Entry에 투표가 집중되는 현실적 패턴도 포함한다.

```
[투표 분포]
상위 5개 Entry에 투표의 50% 집중 (인기 사진)
나머지 Entry에 50% 분산

→ 특정 Entry의 DB 행에 락 경합이 발생하는가?
→ Redis Sorted Set의 특정 key에 부하가 집중되는가?
```

### 2-4. 측정 항목

```
[응답 시간]
- 읽기 API별 p50, p95, p99
- 투표 API p50, p95, p99
- 전체 p95

[처리량]
- 전체 RPS
- API별 RPS
- 성공률 / 에러율

[인프라 지표 (Grafana)]
- HikariCP: active connections, pending threads, acquire time
- Redis: connected clients, commands/sec, memory
- MySQL: queries/sec, slow queries, connections
- EC2: CPU, Memory, Network I/O
- SQS: messages in queue, consumer lag

[에러 분류]
- HTTP 5xx (서버 에러)
- HTTP 429 (리소스 부족)
- Connection timeout
- Read timeout
```

---

## 3. 실행 순서

### Step 1: k6 혼합 부하 스크립트 작성

기존 `01_basic-load-test.js`, `02_staged-load-test.js`는 읽기 전용.
새로운 `03_mixed-load-test.js`를 작성한다.

```
필수 포함 사항:
- 읽기/쓰기 혼합 (비율: 70/30)
- Hot Spot 투표 패턴 (상위 Entry 집중)
- 단계별 VU 증가 (100 → 300 → 500 → 800)
- API별 커스텀 메트릭 (읽기/쓰기 분리 측정)
- think time 포함 (실제 사용자 시뮬레이션)

투표 API:
- POST /v1/entries/{entryId}/votes/test?memberId={random}&strategy=async
- memberId는 VU별 고유 범위에서 랜덤 할당 (중복 투표 방지)
- entryId는 Hot Spot 분포 적용
```

### Step 2: Baseline 측정 (현재 상태)

```
실행: k6 run 03_mixed-load-test.js
환경: 현재 그대로 (Phase 10-3 상태)

기록할 것:
- 각 VU 단계별 API 응답 시간
- "터지는 지점" = p95 > 1초 또는 에러율 > 5% 되는 VU 수
- 터질 때 Grafana 스크린샷 (HikariCP, Redis, CPU)
```

### Step 3: 병목 분석

터진 지점에서 **왜 터졌는지** 분석한다. 가능한 원인들:

```
원인 A: HikariCP 커넥션 포화
  증거: active connections = 30 (max), pending > 0, acquire time 급증
  → 읽기와 쓰기가 커넥션 풀을 놓고 경합

원인 B: DB CPU/IO 포화
  증거: MySQL CPU > 80%, slow queries 증가
  → 쿼리 자체가 무거움 or 쿼리 수가 많음

원인 C: Redis 병목
  증거: Redis commands/sec 급증, latency 증가
  → Lettuce 풀 포화 or 특정 key hot spot

원인 D: App Server CPU 포화
  증거: EC2 CPU > 80%
  → t3.small의 물리적 한계

원인 E: SQS Consumer 지연
  증거: 큐에 메시지 누적, consumer lag 증가
  → DB 저장이 투표 속도를 못 따라감

원인 F: 특정 API가 다른 API를 끌어내림
  증거: 투표 p95는 변함없는데 랭킹 p95만 악화 (또는 반대)
  → 리소스 경합의 방향 확인
```

### Step 4: 가설 수립

**병목 원인에 따라** 가설이 달라진다. 미리 정하지 않는다.

```
만약 원인 A (커넥션 포화):
  가설: "투표의 DB 중복 체크를 Redis로 이전하면
         DB 커넥션 사용을 줄여 읽기 성능이 보호된다"
  또는: "읽기 API에 캐시를 적용하면 DB 커넥션 경합이 줄어든다"

만약 원인 B (DB 부하):
  가설: "랭킹 쿼리가 무거워서 DB CPU를 잡아먹고 있다.
         Redis Sorted Set에서 직접 랭킹을 서빙하면 DB 부하가 줄어든다"
  → 이미 Redis 랭킹이 있는데 DB로 폴백하고 있는지 확인

만약 원인 C (Redis 병목):
  가설: "Lettuce 풀 사이즈가 부족하다. 30으로 늘리면 해소된다"
  또는: "Pipeline으로 명령 수를 줄이면 해소된다"

만약 원인 D (CPU 포화):
  가설: "t3.small의 한계. t3.medium으로 올리면 VU ___까지 버틴다"
  → 인프라 스케일업의 비용 대비 효과 분석

만약 원인 E (SQS 지연):
  가설: "Consumer 동시성을 1 → 5로 올리면 처리량이 비례 증가한다"
  → 데드락 위험과 트레이드오프

만약 원인 F (특정 API 영향):
  가설: "투표가 읽기를 끌어내리고 있다면, 커넥션 풀을 분리하면 격리된다"
  → 읽기 전용 풀 vs 쓰기 전용 풀
```

### Step 5: 해결 및 재측정

가설 기반으로 변경 → **동일 조건으로 재측정** → Before/After 비교

### Step 6: 결론 정리

```
문서화:
- 질문: "혼합 부하에서 몇 VU까지 버티는가?"
- 측정 결과: ___VU에서 ___가 터짐
- 원인: ___
- 가설: ___
- 해결: ___
- Before/After: p95 ___ms → ___ms, 한계 VU ___ → ___
```

---

## 4. 예상 결과 시나리오 (가이드)

실험 결과에 따라 자연스럽게 **Round 2**로 이어진다.

```
시나리오 A: 300 VUs 혼합에서 이미 터진다
  → "읽기 300 VUs는 버텼는데 쓰기 섞으니 못 버틴다"
  → 원인 분석 → Round 2에서 해결

시나리오 B: 500 VUs까지 버틴다
  → "현재 아키텍처가 생각보다 튼튼하다"
  → 800 VUs에서 터지는 지점 분석 → Round 2에서 한계 확장

시나리오 C: 투표만 느려지고 읽기는 괜찮다
  → "DB 커넥션을 쓰기가 많이 잡지만 읽기는 빠르게 반환"
  → Round 2에서 투표 Hot Path 최적화 집중
```

**어떤 결과가 나오든 포트폴리오가 된다.** 측정 → 분석 → 해결의 과정 자체가 가치.

---

## 5. Grafana 대시보드 준비

측정 전에 모니터링이 준비되어야 한다.

```
확인할 패널:
[HikariCP]
- hikaricp_connections_active     (활성 커넥션 수)
- hikaricp_connections_pending    (대기 중인 요청 수)
- hikaricp_connections_acquire_seconds (커넥션 획득 시간)

[JVM]
- jvm_threads_live              (활성 스레드 수)
- jvm_memory_used_bytes         (메모리 사용량)

[HTTP]
- http_server_requests_seconds  (API별 응답 시간)

[Redis]  — redis-exporter 또는 INFO 명령
- connected_clients
- instantaneous_ops_per_sec

[MySQL]  — mysqld-exporter 또는 SHOW STATUS
- Threads_connected
- Queries (per sec)
- Slow_queries

[SQS]  — CloudWatch
- ApproximateNumberOfMessages
- NumberOfMessagesSent/Received
```

현재 Actuator + Prometheus는 설정되어 있으므로 HikariCP, JVM, HTTP 메트릭은 바로 사용 가능. Redis/MySQL 메트릭은 확인 필요.