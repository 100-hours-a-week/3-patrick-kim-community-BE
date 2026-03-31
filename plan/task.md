# PetStar 성능 한계 탐색 — 작업 체크리스트

> 진행 상태: 🔴 시작 전 | 🟡 진행 중 | 🟢 완료

---

## Round 1: 혼합 부하 한계 탐색 🟢

> 질문: "읽기 + 쓰기가 동시에 발생할 때 동시 사용자 몇 명까지 버티는가?"
> 결과 문서: `plan/round-1_결과.md`

### 1-1. 테스트 준비 🟢

- [x] k6 혼합 부하 스크립트 작성 (`k6/03_mixed-load-test.js`)
- [x] Terraform 인프라 구축 (EC2 x2 + RDS + SQS + VPC)
- [x] 앱 빌드 & ECR 푸시 & 배포 (`scripts/full-deploy.sh`)
- [x] Redis 연결 수정 (Public IP → Private IP 10.0.2.166)
- [x] 테스트 데이터 시딩 (Member 10K, Pet 15K, Entry 100K)
- [x] API 동작 확인 (챌린지/랭킹/엔트리/투표 모두 정상)
- [x] Prometheus 연동 확인 (HikariCP, JVM, HTTP 메트릭 수집 중)

### 1-2. Baseline 측정 🟢

- [x] k6 혼합 부하 실행 (10 → 100 → 300 → 500 → 800 VUs)
- [x] 결과 기록 (`k6/results/mixed-load-result.json`)
  - [x] 전체 p95: **5,439ms** (threshold 1,000ms 초과)
  - [x] 에러율: **0.42%** (threshold 5% 이내)
  - [x] 챌린지 목록 p95: 5,426ms
  - [x] 랭킹 p95: 4,708ms
  - [x] 엔트리 p95: **7,002ms** (최악)
  - [x] 투표 p95: 4,149ms
  - [x] HTTP RPS: 120.9 req/s
  - [x] 투표 성공: 5,976건 / 중복: 377건 / 5xx: 0건
  - [x] "터지는 지점": **100 VUs에서 이미 커넥션 풀 포화**

### 1-3. 병목 분석 🟢

- [x] 터진 지점에서 원인 파악
  - [x] **DB 커넥션 포화: 확인됨 (핵심 병목)**
    - Active = 30/30 (100 VUs에서 이미 포화)
    - Pending 최대 170개 스레드
    - Acquire Time 최대 12,419ms
  - [x] DB CPU/IO 포화: **아님** (쿼리 6~19ms로 빠름)
  - [x] Redis 병목: **아님**
  - [x] App Server CPU: 확인 필요 (JVM 스레드 257, 대부분 I/O 대기)
  - [x] SQS Consumer 지연: **아님** (Fire & Forget)
  - [x] 악화 요인: VoteConsistencyScheduler 100K행 풀스캔 (3.7초간 커넥션 점유)
- [x] 병목 원인 문서화 (`plan/round-1_결과.md`)

### 1-4. 가설 수립 🟢

- [x] 가설 1: "투표 DB exists → Redis SADD 전환 시 커넥션 사용 2→1회, p95 50%+ 개선"
- [x] 가설 2: "Entry 메타데이터 Redis 캐시 시 DB 호출 완전 제거, p95 < 500ms"
- [x] 가설 3: "VoteConsistencyScheduler 최적화로 커넥션 1개 절약"

### 1-5. Round 1 결론 정리 🟢

- [x] 결과 문서 작성 (`plan/round-1_결과.md`)

---

## Round 2: 투표 Hot Path 최적화 🟢

> 질문: "투표 API에서 DB를 제거하면 혼합 부하 성능이 개선되는가?"
> 답: **아니다. 병목은 투표가 아니라 읽기 API였다.**
> 결과 문서: `plan/round-2_결과.md`

### 2-1. 실험 A: DB exists → Redis SADD 전환 🟢 (실패 — 개선 없음)

- [x] VoteService.voteAsync() 변경
  - [x] DB exists 호출 제거
  - [x] `recordAndIncrementPipelined()` 적용 (SADD + ZINCRBY + ZSCORE = 1 RTT)
  - [x] SADD 결과가 0(중복)이면 ZINCRBY 롤백
- [x] 변경 후 앱 빌드 & 배포
- [x] 혼합 부하 테스트 재실행 (동일 시나리오)
- [x] Before/After 비교
  - [x] 전체 p95: 5,439ms → **5,835ms (+7% 악화)**
  - [x] 투표 p95: 4,149ms → 4,257ms (+3%)
  - [x] HikariCP active max: 30 → 30 (동일, 풀 포화)
  - [x] HikariCP pending max: 170 → 170 (동일)
  - [x] HikariCP acquire time max: 12,419ms → **15,154ms (+22% 악화)**

### 2-2. 실험 A 결과 분석 🟢

- [x] **실패**: p95 개선 없음, 오히려 미세 악화
- [x] 원인: 읽기 API가 전체 DB 호출의 78%를 차지 → 투표 1회 줄여봐야 미미
- [x] Phase 10-2와의 차이: Lettuce 풀링은 Redis 병목 해소에 성공, BUT 진짜 병목은 읽기
- [x] 투표 중복 377→93으로 감소: Redis SADD가 DB보다 빠른 중복 차단 (정합성 개선)
- [x] → **새 가설: 읽기 API 캐싱이 진짜 해결책**

### 2-3~2-4. 실험 B, C: 스킵

> 실험 A가 실패했으므로 실험 B(findById 제거), C(Lettuce 튜닝) 진행 불필요.
> 읽기 캐싱으로 방향 전환 → Round 3으로 이동.

### 2-5. 혼합 부하 재측정 🔴

- [ ] Round 2 변경사항 적용 상태에서 혼합 부하 재실행
- [ ] Round 1 vs Round 2 결과 비교
  - [ ] 읽기 p95 변화
  - [ ] 투표 p95 변화
  - [ ] 한계 VU 수 변화

### 2-6. Round 2 결론 정리 🔴

- [ ] 결과 문서 작성 (`plan/round-2_결과.md`)
  - [ ] 3막 구조: Phase 10-2 실패 → 풀링 보강 → 재도전 결과
  - [ ] Before/After 수치 정리
  - [ ] 포트폴리오 서술 포인트

---

## Round 3: 읽기 API 캐싱 🟢

> 질문: "읽기 API에 캐시를 적용하면 커넥션 포화가 해소되어 성능이 개선되는가?"
> 전제: Round 2에서 읽기가 DB 호출의 78%를 차지한다고 확인됨
> 가설: "읽기 캐싱으로 DB 커넥션 사용을 90%+ 줄이면 p95 < 1초 달성 가능"
> **결과: p95 5,439ms → 48ms (99.1% 개선). 가설 대비 48배 초과 달성.**
> 결과 문서: `plan/round-3_결과.md`

### 3-1. 읽기 API 캐싱 구현 🟢

- [x] Caffeine 로컬 캐시 의존성 추가 (`spring-boot-starter-cache` + `caffeine`)
- [x] CacheConfig 작성 (TTL 10초, maximumSize 200, recordStats)
- [x] 챌린지 목록 캐싱 (`@Cacheable(value = "challengeList")`)
- [x] 엔트리 목록 캐싱 (`@Cacheable(value = "entryList")`)
- [x] 랭킹 조회 캐싱 (`@Cacheable(value = "ranking")`)
- [x] **Controller 레벨 캐싱** — 캐시 히트 시 DB 커넥션을 아예 획득하지 않음

### 3-2. 빌드 & 배포 & 재측정 🟢

- [x] 앱 빌드 & ECR 푸시 & 배포
- [x] 혼합 부하 테스트 재실행 (동일 시나리오, 동일 VU 단계)
- [x] Before/After 비교
  - [x] 전체 p95: 5,439ms → **48ms (99.1% 개선)**
  - [x] 챌린지 목록 p95: 5,426ms → **66ms (98.8% 개선)**
  - [x] 랭킹 p95: 4,708ms → **38ms (99.2% 개선)**
  - [x] 엔트리 p95: 7,002ms → **49ms (99.3% 개선)**
  - [x] 투표 p95: 4,149ms → **77ms (98.1% 개선)**
  - [x] HTTP RPS: 120.9 → **220.5 (+82.4%)**
  - [x] 투표 성공: 5,976 → **10,836 (+81.3%)**
  - [x] 에러율: 0.42% → **0.43% (동일)**

### 3-3. Round 3 결론 정리 🟢

- [x] 결과 문서 작성 (`plan/round-3_결과.md`)
- [x] 전체 스토리 정리 (Round 1 → 2 실패 → 3 성공)

---

## Round 4: 비동기 투표 파이프라인 장애 대응 🟢

> 질문: "비동기 파이프라인에서 장애가 발생하면 데이터 정합성을 어떻게 보장하는가?"
> 계획 문서: `plan/round-4_계획.md`

### 4-A. 보상 트랜잭션 (SQS 전송 실패 시 Redis 롤백) 🟢

- [x] `VoteService.voteAsync()` 수정: SQS 전송 try-catch 추가
  - [x] 실패 시 `decrementVote()` + `removeVoteRecord()` 호출
  - [x] 에러 로깅 + 적절한 예외 응답

### 4-B. Circuit Breaker (Redis 장애 → Pessimistic Lock Fallback) 🟢

- [x] Resilience4j 의존성 추가 (`build.gradle`)
  - [x] `resilience4j-spring-boot3`
  - [x] `spring-boot-starter-aop`
- [x] Circuit Breaker 설정 (`application-dev.yml`)
  - [x] failureRateThreshold: 50%
  - [x] slidingWindowSize: 10
  - [x] waitDurationInOpenState: 30s
  - [x] permittedNumberOfCallsInHalfOpenState: 3
  - [x] slowCallDurationThreshold: 2s
  - [x] slowCallRateThreshold: 80%
- [x] `VoteService` Circuit Breaker 적용
  - [x] `@CircuitBreaker(name = "redisVote", fallbackMethod = "voteFallback")`
  - [x] `voteFallback()` → `self.votePessimistic()` 호출
- [x] Actuator에 Circuit Breaker 상태 노출 (모니터링)
  - [x] `/actuator/circuitbreakers` 확인
  - [x] `/actuator/health` Circuit Breaker 상태 UP 확인

### 4-C. 빌드 & 배포 & 검증 🟢

- [x] 로컬 빌드 성공 확인
- [x] Docker 빌드 & ECR 푸시 & 배포
- [x] 앱 정상 기동 확인 (health: UP)
- [x] Circuit Breaker 상태 확인 (redisVote: CLOSED)

### 4-D. 포트폴리오 문서 작성 🟢

- [x] `portfolio/02_비동기_파이프라인_정합성_v1.md` 작성
  - [x] 장애 시나리오 4가지 식별 과정
  - [x] 보상 트랜잭션 설계 + 코드
  - [x] Circuit Breaker 설계 (상태 전이 다이어그램, 설정 근거)
  - [x] 다층 방어 아키텍처 (SADD → UK → Scheduler → Circuit Breaker)
  - [x] SP1 Pessimistic Lock이 Fallback으로 연결되는 스토리
- [x] `portfolio/00_프로젝트_개요_v1.md` 업데이트

---

## 최종 산출물 🔴

- [ ] 포트폴리오 문서 최종 정리
  - [ ] SP1: Hot Spot 동시성 (정합성과 성능 트레이드오프)
  - [ ] SP2: 비동기 파이프라인 정합성 (장애 대응 + Circuit Breaker)
  - [ ] SP3: 혼합 부하 가설 검증 (Round 1~3)
- [ ] 전체 Before/After 수치 표 정리
- [ ] Grafana 스크린샷 정리

---

## 부하 테스트 이력

| 날짜 | 테스트 | 결과 요약 | 결과 파일 |
|------|--------|-----------|-----------|
| 2026-03-13 18:50 | Round 1 Baseline (혼합 부하) | p95 5,439ms, 에러 0.42%, HikariCP 포화 30/30 | `k6/results/mixed-load-result.json` |
| 2026-03-13 19:33 | Round 2A (DB exists→Redis SADD) | p95 5,835ms, 에러 0.73%, **개선 없음** | `plan/round-2_결과.md` |
| 2026-03-13 20:32 | **Round 3 (Caffeine L1 캐시)** | **p95 48ms, RPS 220.5, 에러 0.43%, 99.1% 개선** | `plan/round-3_결과.md` |
