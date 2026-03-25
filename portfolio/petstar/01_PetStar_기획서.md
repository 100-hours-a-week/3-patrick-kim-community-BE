# PetStar 기획서

## 프로젝트 개요

PetStar는 반려동물 챌린지 플랫폼이다. 매주 새로운 챌린지가 열리고, 집사들이 자신의 반려동물 사진으로 참여하여 서로 투표하고, 1위에게 보상을 제공하는 서비스다.

### 핵심 흐름
```
챌린지 발표 → 펫 사진으로 참여 → 참여자만 투표 가능 → 일요일 밤 10시 마감 → 1위 결정 → 보상
```

### 프로젝트 목적
이 프로젝트는 **성능 최적화 포트폴리오**를 위한 것이다. 챌린지 마감 직전 트래픽 폭주 상황에서 발생하는 동시성 문제, DB 부하 문제를 해결하고 수치화된 성과를 기록하는 것이 목표다.

---

## 핵심 기능

### MVP (P0)
- 회원가입/로그인 (JWT)
- 펫 등록 (1인 다수 펫 가능)
- 챌린지 목록/상세 조회
- 챌린지 참여 (펫 사진 + 캡션)
- 투표 (참여자만 투표 가능, 본인 제외)
- 실시간 랭킹 조회

### 비즈니스 규칙
- 하나의 챌린지에 같은 펫은 1번만 참여 가능
- 본인 참여작에는 투표 불가
- 이미 투표한 참여작에 중복 투표 불가
- 챌린지 진행 중에만 투표 가능

---

## 데이터 모델

### 엔티티 관계
```
Member (1) ──── (N) Pet
   │                 │
   │                 │
   ├── (N) Entry ────┘ (챌린지 참여작)
   │        │
   │        ├── (N) Vote (투표)
   │        └── (N) SupportMessage (응원 메시지)
   │
Challenge (1) ──── (N) Entry
```

### 핵심 엔티티

**Member**
- id, email, password, nickname, profileImageId, role, createdAt

**Pet**
- id, memberId(FK), name, species(DOG/CAT/BIRD/FISH/ETC), breed, birthDate, gender, profileImageId

**Challenge**
- id, title, description, thumbnailId, status(UPCOMING/ACTIVE/ENDED), startAt, endAt, maxEntries

**Entry**
- id, challengeId(FK), petId(FK), memberId(FK), imageId(FK), caption, voteCount, createdAt
- UNIQUE(challengeId, petId) - 같은 펫 중복 참여 방지

**Vote**
- id, entryId(FK), memberId(FK), createdAt
- UNIQUE(entryId, memberId) - 중복 투표 방지

**SupportMessage**
- id, entryId(FK), memberId(FK), content, createdAt

**Image**
- id, url, originalName, storedName, size, contentType

---

## API 설계

### 인증
```
POST   /api/v1/auth/signup     회원가입
POST   /api/v1/auth/login      로그인
POST   /api/v1/auth/logout     로그아웃
POST   /api/v1/auth/refresh    토큰 갱신
```

### 펫
```
POST   /api/v1/pets            펫 등록
GET    /api/v1/pets            내 펫 목록
GET    /api/v1/pets/{id}       펫 상세
PATCH  /api/v1/pets/{id}       펫 수정
DELETE /api/v1/pets/{id}       펫 삭제
```

### 챌린지
```
GET    /api/v1/challenges                    챌린지 목록 (status 필터)
GET    /api/v1/challenges/{id}               챌린지 상세
GET    /api/v1/challenges/{id}/ranking       챌린지 랭킹 (투표수 기준)
```

### 참여작 (Entry)
```
POST   /api/v1/challenges/{id}/entries       챌린지 참여
GET    /api/v1/challenges/{id}/entries       참여작 목록 (Cursor 페이징)
GET    /api/v1/entries/{id}                  참여작 상세
DELETE /api/v1/entries/{id}                  참여작 삭제 (본인만)
```

### 투표
```
POST   /api/v1/entries/{id}/votes            투표
DELETE /api/v1/entries/{id}/votes            투표 취소
```

### 응원 메시지
```
POST   /api/v1/entries/{id}/supports         응원 메시지 작성
GET    /api/v1/entries/{id}/supports         응원 메시지 목록
DELETE /api/v1/supports/{id}                 응원 메시지 삭제
```

### 이미지
```
POST   /api/v1/images                        이미지 업로드
```

---

## 기술 스택

- Java 17
- Spring Boot 3.2.x
- Spring Data JPA
- Spring Security + JWT
- MySQL 8.0
- Redis 7.x (캐싱, 실시간 랭킹)
- AWS S3 (이미지 저장)
- Gradle 8.x

---

## 성능 최적화 시나리오

이 프로젝트에서 다룰 핵심 성능 문제들:

### 1. 실시간 랭킹 조회 느림
- 문제: ORDER BY vote_count DESC 시 Full Table Scan
- 해결: 복합 인덱스 + Redis Sorted Set

### 2. 참여작 목록 N+1 문제
- 문제: Entry 조회 후 Pet, Member 개별 조회
- 해결: Fetch Join, @BatchSize, DTO Projection

### 3. 투표 동시성 문제
- 문제: 동시 투표 시 중복 발생, vote_count 부정확
- 해결: Unique 제약조건 + 원자적 UPDATE

### 4. 챌린지 마감 트래픽 폭주
- 문제: 마감 직전 동시 접속자 급증
- 해결: Redis 캐싱, Connection Pool 최적화

---

## 테스트 데이터 규모

성능 테스트를 위한 시딩 데이터:
- Member: 10,000건
- Pet: 15,000건
- Challenge: 50건 (진행중 3개)
- Entry: 100,000건
- Vote: 1,000,000건
- SupportMessage: 500,000건
