# PetStar — 반려동물 사진 콘테스트 플랫폼

**기간**: 2026.01 ~ 2026.03 (3개월)
**역할**: 백엔드 개발 (1인)
**기술 스택**: Java 21 / Spring Boot 3.5 / MySQL 8.0 / Redis 7 / AWS SQS / JPA / QueryDSL / Docker / Terraform
**인프라**: AWS EC2(t3.small) / RDS(db.t3.micro) / SQS Standard Queue / S3 / ECR
**모니터링**: Prometheus + Grafana + Micrometer + p6spy
**부하테스트**: k6 (혼합부하 시나리오, 장애 시나리오)

> 반려동물 사진 콘테스트 플랫폼. 주간 챌린지에 반려동물 사진을 출품하고, 투표로 실시간 랭킹이 결정되는 서비스.
> 동시 투표 Hot Spot 문제 해결과 성능 최적화를 중심으로 한 백엔드 프로젝트.

---

## 담당 기능

### 1. PetStar 리브랜딩 (도메인 재설계)

- **[문제]** 기존 커뮤니티 플랫폼(게시글/댓글/좋아요)을 반려동물 콘테스트 플랫폼으로 전환 필요
- **[해결]** 도메인 엔티티를 재설계하여 매핑 (Post→Entry, Like→Vote, Comment→SupportMessage), Pet·Challenge 엔티티 신규 추가, 기존 코드 자산을 최대한 재활용
- **[결과]** 기존 코드베이스 위에 새로운 도메인을 성공적으로 구축, 개발 기간 단축

---

### 2. AWS 인프라 구축 (IaC)

- **[문제]** 수동 인프라 구성은 재현 불가능하고 환경 간 불일치 발생
- **[해결]** Terraform으로 AWS 인프라 전체를 코드화 (VPC, EC2, RDS, SQS, ECR, IAM, Security Group)
- **[결과]** 인프라 전체를 코드로 관리하여 재현 가능한 환경 확보, Docker 컨테이너 + ECR 배포 파이프라인 구축

---

### 3. 모니터링 시스템 구축

- **[문제]** 성능 병목 지점을 파악할 수 있는 관측 도구가 없음
- **[해결]** Prometheus + Grafana로 메트릭 수집/시각화 구축, p6spy로 SQL 쿼리 로깅, Micrometer로 커스텀 메트릭 추가 (HikariCP, JVM, HTTP, Redis, SQS Producer/Consumer)
- **[결과]** HikariCP 커넥션 풀 포화, 쿼리 실행 계획, Redis 응답 시간 등을 실시간으로 모니터링하여 성능 최적화의 근거 데이터 확보

---

### 4. k6 부하 테스트 환경 구축

- **[문제]** 성능 최적화의 Before/After를 정량적으로 측정할 도구가 없음
- **[해결]** k6로 4종류의 부하 테스트 스크립트 설계: 단일 API 테스트, 단계별 부하(10→100→300→500→800 VUs), 혼합 부하(읽기 70% + 투표 30%), 장애 시나리오 테스트
- **[결과]** 시드 데이터(10K Members, 100K Entries) 기반의 재현 가능한 테스트 환경 구축, Hot Spot 시뮬레이션(상위 5개 Entry에 투표 50% 집중) 설계

---

### 5. 랭킹 API 성능 최적화

- **[문제]** 랭킹 조회 시 Full Table Scan + Java Stream 정렬로 p95 4.76s
- **[해결]**
  - Pageable 적용: Java `Stream.limit()` → DB `LIMIT` 전환 (100K row → 10 row 로드) → **p95 81% 개선**
  - Fetch Join: Entry 조회 시 Pet, Member를 JOIN FETCH로 한 번에 로드 → **p95 73% 개선**
  - 복합 인덱스: EXPLAIN에서 filesort 발견 → `(challenge_id, vote_count DESC)` 인덱스 추가 → **p95 38% 개선**
- **[결과]** 읽기 API p95: 4.76s → 130ms (97% 개선), RPS 32.9 → 308 (9.4배 증가)

---

### 6. 참가작 목록 N+1 문제 해결

- **[문제]** 참가작 목록 조회 시 Entry마다 Pet, Member, Image를 개별 조회하여 20건 기준 총 61개 쿼리 발생 (N+1)
- **[해결]** p6spy 쿼리 로그로 N+1 문제 발견 → QueryDSL Fetch Join으로 1개 쿼리로 통합, @BatchSize(20) 글로벌 설정으로 나머지 Lazy 로딩도 IN절 배치 처리
- **[결과]** 쿼리 수 61개 → 1개 (98% 감소), 참가작 목록 응답시간 34% 개선

---

### 7. 투표 동시성 제어

- **[문제]** 50명 동시 투표 시 Check-then-Act Race Condition으로 중복 투표 발생, Lost Update로 투표수 누락
- **[해결]**
  - 3가지 전략 비교 구현 및 테스트: Pessimistic Lock(100% 성공), Optimistic Lock(16% 성공), Atomic+Retry(94% 성공)
  - DB Unique 제약조건 `UNIQUE(entry_id, member_id)`으로 중복 투표 원천 차단
  - 원자적 UPDATE `SET vote_count = vote_count + 1`으로 Lost Update 방지
- **[결과]** Hot Spot 환경에서 데이터 정합성 100% 보장, Pessimistic Lock 채택 (p95 1.73s이지만 유일한 완전 정합성)

---

### 8. HikariCP 커넥션 풀 / JVM 튜닝

- **[문제]** Grafana 메트릭 분석 결과, HikariCP 커넥션 풀이 100 VUs에서 이미 포화 (Active 30/30, Pending 170, 획득 대기 최대 12.4초)
- **[해결]** HikariCP max-pool-size 10→30으로 확장, JVM 옵션 튜닝 (-Xms512m -Xmx1024m -XX:+UseG1GC)
- **[결과]** 커넥션 획득 시간 1.79s → 0.19s (89% 개선), 동시 처리 가능 사용자 수 증가

---

### 9. Redis 실시간 랭킹 시스템

- **[문제]** 랭킹 조회마다 DB ORDER BY 쿼리 실행, 투표 반영에 지연 발생
- **[해결]** Redis Sorted Set으로 실시간 랭킹 구현: 투표 시 ZINCRBY O(log N)으로 점수 증가, 랭킹 조회 시 ZREVRANGE로 상위 N개 즉시 반환
- **[결과]** 랭킹 조회를 Redis에서 즉시 서빙, DB 부하 분리, 사용자에게 투표 결과 즉시 반영

---

### 10. SQS 비동기 투표 시스템

- **[문제]** Pessimistic Lock으로 정합성은 보장했지만, 50명 동시 투표 시 순차 Lock 대기로 p95 1.73s (마지막 사람은 49명을 기다림)
- **[해결]**
  - MQ 후보 비교 분석 (Kafka vs RabbitMQ vs SQS) → SQS 선택 (운영 부담 0, 비용 $1/월, 무제한 확장)
  - Standard vs FIFO Queue 분석 → Standard 선택 (투표는 순서 무관, FIFO의 300 TPS 제한 회피)
  - DB 중복체크 + Redis 즉시 반영 + SQS Fire&Forget 비동기 DB 저장 아키텍처 설계
  - Consumer 순차 처리 (maxConcurrentMessages=1)로 데드락 방지, Idempotency 보장
- **[결과]** p95 1,730ms → 516ms (70% 개선), Lock 대기 완전 제거, 사용자는 Redis 기준 즉시 응답

---

### 11. Lettuce 커넥션 풀링 + Redis Pipeline

- **[문제]** Lettuce 기본 단일 커넥션 멀티플렉싱이 고동시성에서 직렬화 병목 발생 (Phase 10-2에서 p95 1,300ms로 성능 역전 경험)
- **[해결]**
  - commons-pool2 기반 Lettuce 커넥션 풀링 도입 (max-active: 20, min-idle: 5)
  - Redis Pipeline으로 ZINCRBY + ZSCORE 2회 호출을 1 RTT로 통합 (네트워크 왕복 50% 감소)
- **[결과]** p95 688ms → 658ms, Lettuce 단일 커넥션 병목 해소, 고동시성 환경에서 안정적 Redis 처리

---

### 12. Redis↔DB 정합성 스케줄러

- **[문제]** 비동기 아키텍처에서 SQS 전송 실패, Consumer 처리 실패, Redis 재시작 등으로 Redis↔DB 간 데이터 불일치 발생 가능
- **[해결]** VoteConsistencyScheduler 구현 (5분 주기): 활성 챌린지의 모든 Entry에 대해 Redis score vs DB voteCount 비교, 불일치 시 DB 기준 동기화 (Source of Truth = DB)
- **[결과]** Redis > DB일 경우 30초 대기 후 재검증 (SQS 처리 지연 고려), Redis < DB일 경우 즉시 동기화, 최종 정합성 보장

---

### 13. 외부서비스 장애 대응 (Graceful Degradation)

- **[문제]** Redis, SQS 두 외부 서비스에 의존하는 구조에서, 어느 하나가 장애 나면 투표 전체가 중단될 위험
- **[해결]**
  - 5단계 방어선 설계: L1 DB Unique(최종 방어) → L2 Redis 중복체크 → L3 SQS 비동기 → L4 Circuit Breaker → L5 정합성 스케줄러
  - SQS 장애: VoteProducer 동기 전송 전환 → 전송 실패 시 syncWriteToDb() 직접 DB 저장
  - Redis 장애: Resilience4j Circuit Breaker → 실패율 50% 초과 시 OPEN → votePessimistic() 자동 Fallback
  - 동시 장애: DB-only 모드로 Pessimistic Lock Fallback, Redis는 best-effort + 복구 후 스케줄러 동기화
- **[결과]** k6 장애 시나리오 테스트로 검증 — 정상 p95 138ms / SQS 장애 337ms / Redis 장애 1,022ms / 동시 장애 792ms → **어떤 외부서비스가 죽어도 서비스 유지 실증**

---

### 14. DLQ 재처리 스케줄러 + SQS 모니터링

- **[문제]** SQS Consumer에서 3회 이상 실패한 메시지가 Dead Letter Queue에 쌓이면 자동 복구 수단이 없음
- **[해결]** DLQ 재처리 스케줄러 구현 (30분 주기): DLQ에서 메시지를 꺼내 원래 큐로 재전송, Micrometer 메트릭으로 Producer/Consumer/Scheduler 처리량 모니터링
- **[결과]** 실패 메시지 자동 복구, SQS 모니터링 엔드포인트 제공 (`/api/admin/sqs/status`)

---

### 15. 혼합 부하 성능 최적화 (Caffeine 캐시)

- **[문제]** 읽기 70% + 투표 30% 혼합 부하에서 100 VUs만에 HikariCP 포화 (p95 5,439ms), 투표 DB 호출을 줄여도 개선 없음 (Round 2 실패) → 데이터 분석 결과 읽기 API가 전체 DB 호출의 78% 차지
- **[해결]**
  - Caffeine L1 캐시 도입 (TTL 10초, max 200)
  - Controller 레벨 @Cacheable 적용 (캐시 히트 시 DB 커넥션 자체를 획득하지 않음)
  - "가설 실패 → 데이터 분석 → 가설 수정 → 재도전" 사이클로 진짜 병목 해결
- **[결과]** 혼합 부하 p95: 5,439ms → 48ms (99.1% 개선), RPS 120.9 → 220.5 (82.4% 증가), 한계 VU 100 → 800+ (8배 확장)

---

### 16. JWT 인증/인가

- **[문제]** Stateless 환경에서 사용자 인증과 API 보안 필요
- **[해결]** Spring Security + JWT (Access Token 15분 + Refresh Token 7일), JwtAuthenticationFilter 구현, `@LoginUser` 커스텀 어노테이션으로 컨트롤러에 인증 사용자 ID 주입 (ArgumentResolver)
- **[결과]** Stateless 인증 구현, Refresh Token DB 저장으로 로그아웃/토큰 무효화 지원

---

### 17. 이미지 업로드 (S3)

- **[문제]** 반려동물 사진 업로드/관리 기능 필요
- **[해결]** AWS S3에 이미지 업로드 API 구현, Image 엔티티로 메타데이터 관리 (s3Key, url, size, status: USED/UNUSED/DELETED)
- **[결과]** 최대 20MB 이미지 업로드 지원, 상태 관리로 미사용 이미지 추적 가능

---

### 18. 챌린지 + 참가작 + 투표 + 응원메시지 CRUD

- **[문제]** 콘테스트 핵심 도메인의 전체 REST API 필요
- **[해결]**
  - 챌린지: 목록 조회 (상태별 필터 UPCOMING/ACTIVE/ENDED), 상세 조회
  - 참가작: 등록/삭제 + 커서 기반 페이지네이션 (QueryDSL)
  - 투표: 투표/취소 + 중복 방지 (Unique Key)
  - 응원메시지: CRUD + Fetch Join (N+1 방지)
- **[결과]** 12개 Controller, 13개 Service, 11개 Entity로 전체 도메인 API 구현

---

### 19. 환경변수 기반 시크릿 관리

- **[문제]** application.yml에 DB 비밀번호, JWT Secret, AWS 키 등이 하드코딩되어 보안 취약
- **[해결]** 모든 시크릿을 환경변수 참조로 교체 (`${SPRING_DATASOURCE_PASSWORD}`), Docker 배포 시 env-file 방식으로 주입
- **[결과]** Git 레포지토리에 시크릿 노출 방지, 환경별 설정 분리

---

## 트러블슈팅

### Spring AOP Self-Invocation 문제
- **[문제]** VoteService 내부에서 `votePessimistic()` 직접 호출 시 `@Transactional` 미적용 (프록시 우회)
- **[해결]** Self-Injection (`@Lazy @Autowired private VoteService self`)으로 프록시를 통한 호출로 전환
- **[결과]** AOP 기반 어노테이션(`@Transactional`, `@Cacheable` 등) 정상 동작

### SQS Consumer 데드락
- **[문제]** Consumer 병렬 처리 시 같은 Entry에 동시 UPDATE → 데드락 발생 (DB 저장 40% 손실)
- **[해결]** maxConcurrentMessages=1 (순차 처리) + 데드락 시 SQS 재전달 (visibility timeout)
- **[결과]** DB 저장 100% 달성, 데드락 완전 제거

### Lettuce 단일 커넥션 성능 역전
- **[문제]** Redis 중복체크로 전환 시 Lettuce 단일 커넥션 직렬화로 p95 516ms → 1,300ms 역전
- **[해결]** 중복체크를 DB로 복귀 (HikariCP 30개 병렬 처리 활용) + Lettuce 커넥션 풀링 추가
- **[결과]** 각 컴포넌트의 강점을 활용하는 Hybrid 아키텍처 확립 (DB 병렬 체크 + Redis 속도 + SQS 비동기)

### Docker 환경 IMDSv2 접근 불가
- **[문제]** Docker 컨테이너에서 EC2 Instance Metadata 접근 실패 (IMDSv2 hop limit)
- **[해결]** IMDSv2 hop limit 2로 증가 + 기존 IAM User에 SQS 권한 추가
- **[결과]** Docker 환경에서 AWS 자격 증명 정상 작동

---

## 성과 요약

| 지표 | Before | After | 개선율 |
|------|--------|-------|--------|
| 읽기 API p95 (300 VUs) | 4.76s | **130ms** | 97% 개선 |
| 투표 Hot Spot p95 (50 VUs) | 1,730ms | **658ms** | 62% 개선 |
| 혼합 부하 p95 (800 VUs) | 5,439ms | **48ms** | 99.1% 개선 |
| 처리량 (RPS) | 32.9 | **308** (읽기) / **220.5** (혼합) | 9.4배 증가 |
| 동시 처리 한계 | 100명 | **800명+** | 8배 확장 |
| 투표 정합성 | Race Condition | **100%** (다층 방어) | 완전 보장 |
| 외부서비스 장애 | 서비스 중단 | **자동 Fallback** | 가용성 확보 |

---

## 기술적 의사결정

| 의사결정 | 선택 | 근거 |
|---------|------|------|
| 동시성 제어 | Pessimistic Lock | Hot Spot에서 유일한 100% 정합성 (Optimistic 16%, Atomic 12% 실패) |
| 메시지 큐 | AWS SQS Standard | 운영 부담 0, $1/월, Kafka는 오버엔지니어링, FIFO의 300 TPS 제한 회피 |
| 실시간 랭킹 | Redis Sorted Set | O(log N) 모든 연산, DB 부하 분리 |
| 읽기 캐시 | Caffeine L1 (Controller 레벨) | 캐시 히트 시 DB 커넥션 미획득이 핵심, 커넥션 포화 해소 |
| Consumer 동시성 | maxConcurrentMessages=1 | 같은 Entry 동시 UPDATE 데드락 방지, 응답은 Redis에서 이미 완료 |
| 장애 Fallback | Circuit Breaker → Pessimistic Lock | 동기 경로를 Fallback 자산으로 재활용 |
| 인프라 관리 | Terraform (IaC) | VPC/EC2/RDS/SQS/ECR 전체 코드 관리 |
