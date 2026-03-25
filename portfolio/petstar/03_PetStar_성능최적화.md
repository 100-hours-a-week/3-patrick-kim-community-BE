# PetStar 성능 최적화 단계

## 개요

PetStar 프로젝트의 성능 최적화는 7단계로 진행한다. 각 단계마다 Before/After 수치를 측정하여 이력서에 기록할 성과를 만든다.

### 최적화 목표
| 지표 | Before (예상) | After (목표) |
|:---|:---|:---|
| API 평균 응답 시간 | 800ms | 50ms |
| 동시 처리 사용자 | 100명 | 1,000명 |
| 쿼리 수 (목록 조회) | 21개 | 1~3개 |
| 랭킹 조회 응답 | 2초 | 10ms |

### 수치화 패턴
모든 최적화는 A → B → C 패턴으로 기록:
- A (문제): 어떤 성능 문제가 있었는지
- B (해결책): 어떤 기술로 해결했는지
- C (성과): Before → After 수치

---

## Phase 1: 부하테스트 환경 구축

### 목표
- k6를 활용한 부하테스트 환경 구축
- 최적화 전 베이스라인 수치 측정
- 병목 지점 식별

### 작업 내용
1. k6 설치 및 테스트 스크립트 작성
2. 테스트 데이터 시딩 (10만 Entry, 100만 Vote)
3. 베이스라인 측정 (동시 50명, 100명, 500명)

### 측정 대상 API
- GET /api/v1/challenges (챌린지 목록)
- GET /api/v1/challenges/{id}/entries (참여작 목록)
- GET /api/v1/challenges/{id}/ranking (랭킹)
- GET /api/v1/entries/{id} (참여작 상세)
- POST /api/v1/entries/{id}/votes (투표)

### 예상 병목
1. 랭킹 조회: ORDER BY vote_count에 인덱스 없음 → Full Scan
2. 참여작 목록: Pet, Member 개별 조회 → N+1 문제
3. 투표: 동시 요청 시 중복 가능

---

## Phase 2: DB 인덱스 최적화

### 목표
- Full Table Scan 제거
- 쿼리 실행 시간 90% 이상 단축

### 문제 분석
EXPLAIN으로 쿼리 실행 계획 분석:
- 랭킹 조회: type=ALL, Using filesort
- 참여작 목록: type=ALL
- 투표 중복 체크: 100만 건 Full Scan

### 적용할 인덱스

**Entry 테이블**
```
idx_entry_challenge_id (challenge_id, id DESC)       -- 참여작 목록 Cursor 페이징
idx_entry_challenge_vote (challenge_id, vote_count DESC) -- 랭킹 조회
idx_entry_pet_id (pet_id)                            -- 펫별 참여 내역
idx_entry_member_id (member_id)                      -- 회원별 참여 내역
```

**Vote 테이블**
```
uk_vote_entry_member (entry_id, member_id) UNIQUE    -- 중복 투표 방지 + 조회
```

**SupportMessage 테이블**
```
idx_support_entry_id (entry_id, id DESC)             -- 응원 메시지 목록
```

### 기대 성과
- 랭킹 조회: 1,800ms → 35ms (98% 단축)
- 투표 중복 체크: 420ms → 1ms (99.8% 단축)

---

## Phase 3: JPA 쿼리 최적화 (N+1 해결)

### 목표
- N+1 문제 완전 해결
- API당 쿼리 수 3개 이하

### 문제 분석
참여작 목록 조회 시 발생하는 쿼리:
```
1. SELECT * FROM entry WHERE challenge_id = ? (1개)
2. SELECT * FROM pet WHERE id = ? (N개)
3. SELECT * FROM member WHERE id = ? (N개)
4. SELECT * FROM image WHERE id = ? (N개)

→ 20개 조회 시 총 61개 쿼리
```

### 해결 방법

**1. Fetch Join**
Entry 조회 시 Pet, Member를 한 번에 JOIN FETCH

**2. @BatchSize**
글로벌 설정으로 IN 절 배치 조회 (61개 → 4개)

**3. DTO Projection**
필요한 컬럼만 SELECT하는 네이티브 쿼리

### 적용 대상
- 참여작 목록 조회 (findEntriesWithDetails)
- 랭킹 조회 (findRankingByChallenge)
- 응원 메시지 목록 (findSupportsWithMember)

### 기대 성과
- 참여작 목록: 61쿼리 → 1쿼리 (98% 감소)
- 응답 시간: 650ms → 80ms (87% 개선)

---

## Phase 4: 트랜잭션 & 동시성 제어

### 목표
- 투표 중복 방지 100% 보장
- vote_count 정확성 보장 (Lost Update 방지)

### 문제 시나리오

**시나리오 1: 중복 투표**
```
T1: Thread A 중복 체크 → 없음
T2: Thread B 중복 체크 → 없음
T3: Thread A INSERT → 성공
T4: Thread B INSERT → 성공 (중복!)
```

**시나리오 2: Lost Update**
```
T1: Thread A SELECT vote_count = 100
T2: Thread B SELECT vote_count = 100
T3: Thread A UPDATE vote_count = 101
T4: Thread B UPDATE vote_count = 101 (Lost!)
```

### 해결 방법

**1. 중복 투표 방지**
- DB Unique 제약조건: UNIQUE(entry_id, member_id)
- 예외 처리: DataIntegrityViolationException → 409 응답

**2. vote_count 정확성**
- 원자적 UPDATE: `UPDATE entry SET vote_count = vote_count + 1 WHERE id = ?`
- @Modifying 쿼리 사용

### 검증 방법
- 동시성 테스트: 100개 스레드로 동시 투표 후 정확성 확인

### 기대 성과
- 중복 투표: 5~10% → 0%
- vote_count 정확도: 95% → 100%

---

## Phase 5: 서버 코드 최적화

### 목표
- 비동기 처리로 응답 시간 단축
- 커넥션 풀 최적화

### 최적화 항목

**1. 비동기 처리**
투표 후 알림 발송을 @Async로 분리
- 응답에 영향 주지 않는 작업을 백그라운드로

**2. 커넥션 풀 설정**
HikariCP 최적화:
- maximum-pool-size: 20
- minimum-idle: 5
- connection-timeout: 30000

**3. 불필요한 조회 제거**
- getReferenceById() 사용 (프록시 객체)
- 필요한 필드만 조회

### 기대 성과
- 투표 API: 150ms → 80ms (47% 개선)

---

## Phase 6: Redis 캐싱 도입

### 목표
- 실시간 랭킹 10ms 이내 응답
- DB 부하 60% 감소

### 적용 대상

**1. 실시간 랭킹 (Redis Sorted Set)**
- 투표 시: ZINCRBY로 점수 증가
- 랭킹 조회: ZREVRANGE로 상위 N개 조회 (O(log N))
- DB는 주기적 동기화

**2. 챌린지 목록 (@Cacheable)**
- TTL 5분
- 챌린지 변경 시 @CacheEvict

**3. 참여작 상세 (@Cacheable)**
- TTL 10분
- 투표/삭제 시 @CacheEvict

### Redis 키 설계
```
ranking:challenge:{challengeId}     Sorted Set (score=voteCount, member=entryId)
challenges:ACTIVE                   String (JSON)
entry:{entryId}                     String (JSON)
voted:{memberId}:{challengeId}      Set (투표한 entryId 목록)
```

### 기대 성과
- 랭킹 조회: 60ms → 8ms (87% 개선)
- DB 쿼리 수: 65% 감소

---

## Phase 7: 최종 성능 검증

### 목표
- Before/After 수치 비교
- 이력서용 성과 문서화

### 테스트 조건
- 동시 사용자: 1,000명
- 테스트 시간: 10분
- k6 시나리오: 복합 (읽기 70%, 투표 20%, 쓰기 10%)

### 최종 목표 수치
| API | Before | After | 개선율 |
|:---|:---|:---|:---|
| 챌린지 목록 | 450ms | 12ms | 97% |
| 참여작 목록 | 850ms | 35ms | 96% |
| 랭킹 조회 | 2,100ms | 10ms | 99.5% |
| 투표 | 180ms | 45ms | 75% |

| 지표 | Before | After |
|:---|:---|:---|
| 평균 응답 | 687ms | 28ms |
| RPS | 150 | 2,800 |
| 에러율 | 12% | 0.1% |

---

## 이력서용 성과 요약

### 한 줄 요약
```
"커뮤니티 서비스의 실시간 랭킹 조회 응답 시간을 2.1초 → 10ms로 99.5% 개선하고,
동시 사용자 1,000명을 처리할 수 있는 시스템 구축"
```

### 기술별 성과

**DB 인덱스 최적화**
```
"챌린지 랭킹 조회 API에서 Full Table Scan 문제를 복합 인덱스로 해결,
쿼리 시간 1,800ms → 35ms (98% 단축)"
```

**N+1 문제 해결**
```
"참여작 목록 조회에서 N+1 문제를 Fetch Join으로 해결,
쿼리 수 61개 → 1개 (98% 감소), 응답 시간 87% 개선"
```

**동시성 제어**
```
"투표 동시 요청 시 중복 발생 문제를 Unique 제약 + 원자적 UPDATE로 해결,
동시 1,000명 테스트에서 데이터 정합성 100% 보장"
```

**Redis 캐싱**
```
"실시간 랭킹을 Redis Sorted Set으로 구현,
응답 시간 2.1초 → 10ms (99.5% 개선)"
```
