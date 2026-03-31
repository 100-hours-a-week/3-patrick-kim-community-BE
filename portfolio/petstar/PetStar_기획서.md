# 🐾 PetStar - 기획서

> **"우리 집 막내가 이번 주 스타다!"**  
> 반려동물 챌린지 플랫폼

---

## 📋 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [핵심 가치 제안](#2-핵심-가치-제안)
3. [타겟 사용자](#3-타겟-사용자)
4. [핵심 기능 정의](#4-핵심-기능-정의)
5. [사용자 시나리오](#5-사용자-시나리오)
6. [데이터 모델 (ERD)](#6-데이터-모델-erd)
7. [API 명세](#7-api-명세)
8. [화면 구성](#8-화면-구성)
9. [기술 스택](#9-기술-스택)
10. [보상 시스템](#10-보상-시스템)
11. [성능 최적화 포인트](#11-성능-최적화-포인트)
12. [확장 로드맵](#12-확장-로드맵)

---

## 1. 프로젝트 개요

### 1.1 배경

```
📊 반려동물 시장 현황
- 국내 반려동물 양육 가구: 약 1,500만 가구 (2024년 기준)
- 반려동물 관련 시장 규모: 약 6조원
- "펫팸족(Pet + Family)" 문화 확산
```

반려동물을 키우는 사람들(집사)에게 **"내 아이 자랑"**은 가장 큰 즐거움 중 하나입니다.
인스타그램, 유튜브 등에서 펫 컨텐츠가 폭발적으로 성장하고 있지만, **반려동물 전용 커뮤니티**는 부족한 상황입니다.

### 1.2 문제 정의

| 문제 | 설명 |
|:---|:---|
| 펫 컨텐츠 분산 | 인스타, 유튜브, 커뮤니티 등 여러 플랫폼에 흩어져 있음 |
| 참여 동기 부족 | 단순 좋아요 외에 적극적 참여 유도 장치 없음 |
| 보상 체계 부재 | 인기 있는 펫에게 주어지는 실질적 혜택 없음 |
| 커뮤니티 부재 | 집사들끼리 소통할 수 있는 전용 공간 부족 |

### 1.3 솔루션

**PetStar**는 주기적인 **"펫 챌린지"**를 통해 집사들이 자신의 반려동물을 등록하고, 서로 투표하며, 1위를 향해 경쟁하는 플랫폼입니다.

```
핵심 컨셉:
1. 매주 새로운 챌린지 주제 발표
2. 집사들이 자기 펫 사진/영상으로 참여
3. 참여자들끼리 서로 투표 (참여해야 투표 가능)
4. 주간 1위 펫 → 실제 보상 (펫 용품, 기부, 광고 등)
```

### 1.4 프로젝트 목표

| 목표 | 설명 |
|:---|:---|
| **기술적 목표** | 대용량 트래픽 처리, 동시성 제어, 실시간 랭킹 구현 |
| **비즈니스 목표** | 펫 커뮤니티 활성화, 반려동물 브랜드 협업 |
| **개인 목표** | 성능 최적화 경험, 포트폴리오 완성 |

---

## 2. 핵심 가치 제안

### 2.1 집사 (일반 사용자)

```
✅ "내 아이가 1위가 될 수 있다!"
   - 챌린지 참여 → 투표 받기 → 순위 상승 → 1위 달성

✅ "매주 새로운 재미"
   - 다양한 주제의 챌린지 (귀여운 잠자는 모습, 웃긴 표정 등)

✅ "실질적인 보상"
   - 1위 펫에게 펫 용품, 사료, 굿즈 등 제공

✅ "같은 집사들과의 소통"
   - 응원 메시지, 댓글, 팔로우 기능
```

### 2.2 서비스 차별점

| 기존 서비스 | PetStar |
|:---|:---|
| 인스타그램 | 좋아요만 있음 → **챌린지 + 순위 + 보상** |
| 펫 커뮤니티 | 단순 게시판 → **게임화된 참여 구조** |
| 콘테스트 앱 | 일회성 → **매주 지속적인 챌린지** |

---

## 3. 타겟 사용자

### 3.1 주 타겟

```
페르소나: "자랑하고 싶은 집사"

- 연령: 20~40대
- 특징: SNS 활동 활발, 펫 사진 자주 공유
- 니즈: 내 아이 자랑, 다른 펫 구경, 커뮤니티 활동
- 행동: 매일 1회 이상 앱 접속, 챌린지 참여
```

### 3.2 보조 타겟

```
페르소나: "구경꾼 집사"

- 특징: 직접 참여보다 구경 선호
- 니즈: 귀여운 펫 사진/영상 보기, 힐링
- 행동: 투표 참여, 댓글 작성
```

### 3.3 사용자 세그먼트

| 세그먼트 | 비율 | 특징 |
|:---|:---:|:---|
| 열성 참여자 | 20% | 매주 챌린지 참여, 1위 목표 |
| 일반 참여자 | 40% | 가끔 참여, 투표 활발 |
| 구경꾼 | 30% | 투표만 참여, 컨텐츠 소비 |
| 이탈 유저 | 10% | 가입 후 미접속 |

---

## 4. 핵심 기능 정의

### 4.1 기능 목록

```
[회원]
- 회원가입 / 로그인 / 로그아웃
- 프로필 관리 (닉네임, 프로필 이미지)
- 내 펫 등록 / 관리

[챌린지]
- 챌린지 목록 조회 (진행중 / 종료)
- 챌린지 상세 조회
- 챌린지 참여 (펫 사진 등록)
- 챌린지 참여작 목록 조회

[투표]
- 참여작에 투표하기
- 내가 한 투표 목록 조회
- 실시간 랭킹 조회

[응원]
- 참여작에 응원 메시지 작성
- 응원 메시지 목록 조회

[마이페이지]
- 내 참여 내역
- 내 펫 순위 히스토리
- 받은 보상 내역

[알림]
- 챌린지 시작/마감 알림
- 내 펫 순위 변동 알림
- 응원 메시지 알림
```

### 4.2 기능 우선순위 (MVP)

```
🔴 P0 (필수 - MVP)
├── 회원가입/로그인
├── 펫 등록
├── 챌린지 조회
├── 챌린지 참여
├── 투표하기
└── 실시간 랭킹

🟡 P1 (중요 - MVP 이후)
├── 응원 메시지
├── 마이페이지
├── 알림
└── 팔로우

🟢 P2 (추후 - 확장)
├── 포인트/미션 시스템
├── 1위 보상 시스템 자동화
├── 펫 프로필 페이지
└── 소셜 공유
```

---

## 5. 사용자 시나리오

### 5.1 신규 가입 ~ 첫 참여

```
1. 앱 다운로드 & 회원가입
   └── 이메일 + 비밀번호 + 닉네임

2. 내 펫 등록
   └── 이름: "초코"
   └── 종류: 강아지 (말티즈)
   └── 사진 업로드

3. 진행중인 챌린지 확인
   └── 이번 주 챌린지: "가장 늘어진 모습"

4. 챌린지 참여
   └── 내 펫 "초코" 선택
   └── 챌린지에 맞는 사진 업로드
   └── 한 줄 소개: "소파에서 녹아내린 초코"

5. 참여 완료 → 투표권 획득
   └── "참여해야 투표 가능" 규칙
```

### 5.2 투표 시나리오

```
1. 챌린지 참여작 목록 조회
   └── 최신순 / 인기순 정렬

2. 마음에 드는 참여작에 투표
   └── 하루 최대 10표 제한
   └── 같은 참여작에 중복 투표 불가

3. 응원 메시지 작성 (선택)
   └── "너무 귀여워요! 힘내세요~"

4. 실시간 랭킹 확인
   └── 내 펫 현재 순위: 23위
   └── 1위까지 152표 차이
```

### 5.3 챌린지 마감 시나리오 (핵심!)

```
📅 일요일 밤 10시 - 챌린지 마감 1시간 전

상황:
- 현재 1위: "나비" (고양이) - 1,523표
- 현재 2위: "초코" (강아지) - 1,498표
- 표차: 25표

발생하는 일:
- 1위 경쟁 치열 → 집사들 총동원
- "초코 집사" 친구들에게 투표 독려
- 마감 10분 전 → 동시 접속 폭주
- 초당 수백 건의 투표 요청

⚠️ 기술적 챌린지:
- 동시성 문제 (중복 투표 방지)
- 실시간 랭킹 업데이트
- 서버 부하 처리
```

### 5.4 1위 달성 시나리오

```
📅 일요일 밤 10시 - 챌린지 마감

결과:
- 🥇 1위: "초코" - 1,612표
- 🥈 2위: "나비" - 1,589표
- 🥉 3위: "뭉치" - 1,203표

1위 보상:
- 펫스타그램 공식 인스타 소개
- 반려동물 사료 브랜드 협찬 (1개월분)
- "이 주의 스타" 뱃지 부여
- (선택) 유기동물 보호소에 사료 기부
```

---

## 6. 데이터 모델 (ERD)

### 6.1 엔티티 목록

```
[핵심 엔티티]
- Member (회원)
- Pet (반려동물)
- Challenge (챌린지)
- Entry (챌린지 참여작)
- Vote (투표)
- SupportMessage (응원 메시지)
- Image (이미지)

[보조 엔티티]
- Notification (알림)
- Reward (보상 내역)
- Follow (팔로우)
```

### 6.2 ERD 상세

```
┌─────────────────────────────────────────────────────────────────┐
│                           Member                                 │
├─────────────────────────────────────────────────────────────────┤
│ id (PK)           BIGINT        AUTO_INCREMENT                  │
│ email             VARCHAR(255)  UNIQUE, NOT NULL                │
│ password          VARCHAR(255)  NOT NULL                        │
│ nickname          VARCHAR(20)   NOT NULL                        │
│ profile_image_id  BIGINT        FK → Image                      │
│ role              ENUM          USER, ADMIN                     │
│ created_at        DATETIME      NOT NULL                        │
│ updated_at        DATETIME      NOT NULL                        │
│ deleted_at        DATETIME      NULL (Soft Delete)              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ 1:N
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                             Pet                                  │
├─────────────────────────────────────────────────────────────────┤
│ id (PK)           BIGINT        AUTO_INCREMENT                  │
│ member_id (FK)    BIGINT        NOT NULL                        │
│ name              VARCHAR(50)   NOT NULL                        │
│ species           ENUM          DOG, CAT, BIRD, FISH, ETC       │
│ breed             VARCHAR(50)   NULL (품종)                      │
│ birth_date        DATE          NULL                            │
│ gender            ENUM          MALE, FEMALE, UNKNOWN           │
│ profile_image_id  BIGINT        FK → Image                      │
│ introduction      VARCHAR(200)  NULL                            │
│ created_at        DATETIME      NOT NULL                        │
│ updated_at        DATETIME      NOT NULL                        │
│ deleted_at        DATETIME      NULL                            │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                          Challenge                               │
├─────────────────────────────────────────────────────────────────┤
│ id (PK)           BIGINT        AUTO_INCREMENT                  │
│ title             VARCHAR(100)  NOT NULL                        │
│ description       TEXT          NOT NULL                        │
│ thumbnail_id      BIGINT        FK → Image                      │
│ status            ENUM          UPCOMING, ACTIVE, ENDED         │
│ start_at          DATETIME      NOT NULL                        │
│ end_at            DATETIME      NOT NULL                        │
│ max_entries       INT           NULL (참여 제한, NULL=무제한)    │
│ created_at        DATETIME      NOT NULL                        │
│ updated_at        DATETIME      NOT NULL                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ 1:N
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                            Entry                                 │
├─────────────────────────────────────────────────────────────────┤
│ id (PK)           BIGINT        AUTO_INCREMENT                  │
│ challenge_id (FK) BIGINT        NOT NULL                        │
│ pet_id (FK)       BIGINT        NOT NULL                        │
│ member_id (FK)    BIGINT        NOT NULL                        │
│ image_id (FK)     BIGINT        NOT NULL                        │
│ caption           VARCHAR(200)  NULL                            │
│ vote_count        INT           DEFAULT 0                       │
│ created_at        DATETIME      NOT NULL                        │
│ updated_at        DATETIME      NOT NULL                        │
│ deleted_at        DATETIME      NULL                            │
│                                                                 │
│ UNIQUE (challenge_id, pet_id) -- 챌린지당 펫 1회 참여           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ 1:N
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                             Vote                                 │
├─────────────────────────────────────────────────────────────────┤
│ id (PK)           BIGINT        AUTO_INCREMENT                  │
│ entry_id (FK)     BIGINT        NOT NULL                        │
│ member_id (FK)    BIGINT        NOT NULL                        │
│ created_at        DATETIME      NOT NULL                        │
│                                                                 │
│ UNIQUE (entry_id, member_id) -- 중복 투표 방지                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       SupportMessage                             │
├─────────────────────────────────────────────────────────────────┤
│ id (PK)           BIGINT        AUTO_INCREMENT                  │
│ entry_id (FK)     BIGINT        NOT NULL                        │
│ member_id (FK)    BIGINT        NOT NULL                        │
│ content           VARCHAR(300)  NOT NULL                        │
│ created_at        DATETIME      NOT NULL                        │
│ deleted_at        DATETIME      NULL                            │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                            Image                                 │
├─────────────────────────────────────────────────────────────────┤
│ id (PK)           BIGINT        AUTO_INCREMENT                  │
│ url               VARCHAR(500)  NOT NULL                        │
│ original_name     VARCHAR(255)  NOT NULL                        │
│ stored_name       VARCHAR(255)  NOT NULL                        │
│ size              BIGINT        NOT NULL (bytes)                │
│ content_type      VARCHAR(50)   NOT NULL                        │
│ created_at        DATETIME      NOT NULL                        │
└─────────────────────────────────────────────────────────────────┘
```

### 6.3 인덱스 설계 (최적화 포인트)

```sql
-- Member
CREATE INDEX idx_member_email ON member(email);

-- Pet
CREATE INDEX idx_pet_member_id ON pet(member_id);

-- Challenge
CREATE INDEX idx_challenge_status ON challenge(status);
CREATE INDEX idx_challenge_end_at ON challenge(end_at);

-- Entry
CREATE INDEX idx_entry_challenge_id ON entry(challenge_id);
CREATE INDEX idx_entry_pet_id ON entry(pet_id);
CREATE INDEX idx_entry_member_id ON entry(member_id);
CREATE INDEX idx_entry_challenge_vote ON entry(challenge_id, vote_count DESC);
-- 복합 인덱스: 챌린지별 랭킹 조회 최적화

-- Vote (가장 중요!)
CREATE UNIQUE INDEX uk_vote_entry_member ON vote(entry_id, member_id);
-- 중복 투표 방지 + 조회 최적화

-- SupportMessage
CREATE INDEX idx_support_entry_id ON support_message(entry_id);
```

---

## 7. API 명세

### 7.1 인증 (Auth)

```
POST   /api/v1/auth/signup          회원가입
POST   /api/v1/auth/login           로그인
POST   /api/v1/auth/logout          로그아웃
POST   /api/v1/auth/refresh         토큰 갱신
```

#### 7.1.1 회원가입
```
POST /api/v1/auth/signup

Request:
{
  "email": "user@example.com",
  "password": "password123!",
  "nickname": "초코집사"
}

Response (201):
{
  "success": true,
  "data": {
    "memberId": 1,
    "email": "user@example.com",
    "nickname": "초코집사"
  }
}
```

#### 7.1.2 로그인
```
POST /api/v1/auth/login

Request:
{
  "email": "user@example.com",
  "password": "password123!"
}

Response (200):
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1...",
    "refreshToken": "eyJhbGciOiJIUzI1...",
    "expiresIn": 1800
  }
}
```

---

### 7.2 펫 (Pet)

```
POST   /api/v1/pets                 펫 등록
GET    /api/v1/pets                 내 펫 목록 조회
GET    /api/v1/pets/{petId}         펫 상세 조회
PATCH  /api/v1/pets/{petId}         펫 정보 수정
DELETE /api/v1/pets/{petId}         펫 삭제
```

#### 7.2.1 펫 등록
```
POST /api/v1/pets
Authorization: Bearer {token}

Request:
{
  "name": "초코",
  "species": "DOG",
  "breed": "말티즈",
  "birthDate": "2020-03-15",
  "gender": "MALE",
  "profileImageId": 1,
  "introduction": "낮잠을 좋아하는 3살 말티즈입니다"
}

Response (201):
{
  "success": true,
  "data": {
    "petId": 1,
    "name": "초코",
    "species": "DOG",
    "profileImageUrl": "https://s3.../image.jpg"
  }
}
```

---

### 7.3 챌린지 (Challenge)

```
GET    /api/v1/challenges                     챌린지 목록 조회
GET    /api/v1/challenges/{challengeId}       챌린지 상세 조회
GET    /api/v1/challenges/{challengeId}/ranking  챌린지 랭킹 조회
```

#### 7.3.1 챌린지 목록 조회
```
GET /api/v1/challenges?status=ACTIVE&page=0&size=10

Response (200):
{
  "success": true,
  "data": {
    "challenges": [
      {
        "challengeId": 1,
        "title": "가장 늘어진 모습",
        "description": "소파, 바닥, 어디든! 가장 늘어진 모습을 보여주세요",
        "thumbnailUrl": "https://s3.../thumbnail.jpg",
        "status": "ACTIVE",
        "startAt": "2025-01-27T00:00:00",
        "endAt": "2025-02-02T22:00:00",
        "entryCount": 1523,
        "remainingTime": "2일 3시간"
      }
    ],
    "totalPages": 5,
    "totalElements": 48,
    "hasNext": true
  }
}
```

#### 7.3.2 챌린지 랭킹 조회 (실시간)
```
GET /api/v1/challenges/{challengeId}/ranking?limit=100

Response (200):
{
  "success": true,
  "data": {
    "challengeId": 1,
    "rankings": [
      {
        "rank": 1,
        "entryId": 42,
        "petName": "나비",
        "petImageUrl": "https://s3.../pet.jpg",
        "entryImageUrl": "https://s3.../entry.jpg",
        "ownerNickname": "나비맘",
        "voteCount": 1523,
        "caption": "완전 녹아버린 나비"
      },
      {
        "rank": 2,
        "entryId": 37,
        "petName": "초코",
        "voteCount": 1498,
        ...
      }
    ],
    "lastUpdated": "2025-01-30T21:45:00"
  }
}
```

---

### 7.4 참여 (Entry)

```
POST   /api/v1/challenges/{challengeId}/entries    챌린지 참여
GET    /api/v1/challenges/{challengeId}/entries    참여작 목록 조회
GET    /api/v1/entries/{entryId}                   참여작 상세 조회
DELETE /api/v1/entries/{entryId}                   참여 취소
```

#### 7.4.1 챌린지 참여
```
POST /api/v1/challenges/{challengeId}/entries
Authorization: Bearer {token}

Request:
{
  "petId": 1,
  "imageId": 5,
  "caption": "소파에서 녹아내린 초코"
}

Response (201):
{
  "success": true,
  "data": {
    "entryId": 156,
    "challengeId": 1,
    "petName": "초코",
    "imageUrl": "https://s3.../entry.jpg",
    "caption": "소파에서 녹아내린 초코",
    "createdAt": "2025-01-30T15:30:00"
  }
}

Error (400) - 이미 참여한 경우:
{
  "success": false,
  "error": {
    "code": "ALREADY_PARTICIPATED",
    "message": "이미 이 챌린지에 참여했습니다"
  }
}
```

#### 7.4.2 참여작 목록 조회 (Cursor 기반)
```
GET /api/v1/challenges/{challengeId}/entries?sort=POPULAR&cursorId=100&limit=20

Query Parameters:
- sort: LATEST(최신순) | POPULAR(인기순)
- cursorId: 마지막으로 조회한 entry ID
- limit: 조회 개수 (default: 20, max: 50)

Response (200):
{
  "success": true,
  "data": {
    "entries": [
      {
        "entryId": 99,
        "pet": {
          "petId": 1,
          "name": "초코",
          "profileImageUrl": "https://s3.../pet.jpg"
        },
        "owner": {
          "memberId": 1,
          "nickname": "초코집사"
        },
        "imageUrl": "https://s3.../entry.jpg",
        "caption": "소파에서 녹아내린 초코",
        "voteCount": 342,
        "isVoted": false,
        "createdAt": "2025-01-30T15:30:00"
      }
    ],
    "nextCursorId": 78,
    "hasNext": true
  }
}
```

---

### 7.5 투표 (Vote) ⭐ 핵심 API

```
POST   /api/v1/entries/{entryId}/votes    투표하기
DELETE /api/v1/entries/{entryId}/votes    투표 취소
GET    /api/v1/votes/my                   내 투표 내역
```

#### 7.5.1 투표하기 (동시성 핵심!)
```
POST /api/v1/entries/{entryId}/votes
Authorization: Bearer {token}

Response (200):
{
  "success": true,
  "data": {
    "entryId": 42,
    "newVoteCount": 1524,
    "message": "투표가 완료되었습니다"
  }
}

Error (400) - 중복 투표:
{
  "success": false,
  "error": {
    "code": "ALREADY_VOTED",
    "message": "이미 투표한 참여작입니다"
  }
}

Error (400) - 본인 참여작:
{
  "success": false,
  "error": {
    "code": "CANNOT_VOTE_OWN",
    "message": "본인의 참여작에는 투표할 수 없습니다"
  }
}

Error (400) - 참여자만 투표 가능:
{
  "success": false,
  "error": {
    "code": "PARTICIPATION_REQUIRED",
    "message": "이 챌린지에 참여해야 투표할 수 있습니다"
  }
}
```

---

### 7.6 응원 메시지 (Support)

```
POST   /api/v1/entries/{entryId}/supports              응원 메시지 작성
GET    /api/v1/entries/{entryId}/supports              응원 메시지 목록
DELETE /api/v1/supports/{supportId}                    응원 메시지 삭제
```

#### 7.6.1 응원 메시지 작성
```
POST /api/v1/entries/{entryId}/supports
Authorization: Bearer {token}

Request:
{
  "content": "너무 귀여워요! 1위 응원합니다 🐾"
}

Response (201):
{
  "success": true,
  "data": {
    "supportId": 1,
    "content": "너무 귀여워요! 1위 응원합니다 🐾",
    "createdAt": "2025-01-30T15:35:00"
  }
}
```

---

### 7.7 이미지 (Image)

```
POST   /api/v1/images                 이미지 업로드
DELETE /api/v1/images/{imageId}       이미지 삭제
```

#### 7.7.1 이미지 업로드
```
POST /api/v1/images
Content-Type: multipart/form-data
Authorization: Bearer {token}

Form Data:
- file: (binary)

Response (201):
{
  "success": true,
  "data": {
    "imageId": 1,
    "url": "https://s3.../images/abc123.jpg",
    "originalName": "my_pet.jpg",
    "size": 1024000
  }
}
```

---

### 7.8 마이페이지 (My)

```
GET    /api/v1/my/profile             내 프로필 조회
PATCH  /api/v1/my/profile             내 프로필 수정
GET    /api/v1/my/entries             내 참여 내역
GET    /api/v1/my/votes               내 투표 내역
```

---

## 8. 화면 구성

### 8.1 화면 목록

```
[인증]
- 스플래시 화면
- 로그인 화면
- 회원가입 화면

[메인]
- 홈 화면 (진행중인 챌린지)
- 챌린지 상세 화면
- 참여작 목록 화면
- 참여작 상세 화면 (투표/응원)
- 챌린지 참여 화면

[마이]
- 마이페이지
- 내 펫 관리 화면
- 펫 등록/수정 화면
- 내 참여 내역 화면
- 설정 화면
```

### 8.2 주요 화면 상세

#### 8.2.1 홈 화면
```
┌─────────────────────────────────┐
│  🐾 PetStar           [알림][내정보]│
├─────────────────────────────────┤
│                                 │
│  🔥 진행중인 챌린지               │
│  ┌─────────────────────────┐   │
│  │  [썸네일 이미지]          │   │
│  │                         │   │
│  │  가장 늘어진 모습         │   │
│  │  마감까지 2일 3시간       │   │
│  │  참여 1,523명            │   │
│  │                         │   │
│  │  [참여하기]  [구경하기]    │   │
│  └─────────────────────────┘   │
│                                 │
│  📅 예정된 챌린지               │
│  ┌─────────────────────────┐   │
│  │  다음 주: "웃긴 표정 대회"  │   │
│  └─────────────────────────┘   │
│                                 │
│  🏆 지난 챌린지                 │
│  ┌───┐ ┌───┐ ┌───┐            │
│  │ 1 │ │ 2 │ │ 3 │            │
│  └───┘ └───┘ └───┘            │
│                                 │
├─────────────────────────────────┤
│  [홈]  [챌린지]  [MY]           │
└─────────────────────────────────┘
```

#### 8.2.2 챌린지 상세 / 랭킹 화면
```
┌─────────────────────────────────┐
│  ←  가장 늘어진 모습      [공유] │
├─────────────────────────────────┤
│                                 │
│  마감까지 01:23:45              │
│  ████████████░░░ 85%            │
│                                 │
│  [최신순 ▼]        총 1,523명   │
│                                 │
│  🏆 실시간 랭킹                 │
│  ┌─────────────────────────┐   │
│  │ 1  🥇 나비 (나비맘)       │   │
│  │    1,523표  +24 ↑        │   │
│  ├─────────────────────────┤   │
│  │ 2  🥈 초코 (초코집사)     │   │
│  │    1,498표  +18 ↑        │   │
│  ├─────────────────────────┤   │
│  │ 3  🥉 뭉치 (뭉치아빠)     │   │
│  │    1,203표  +5 ↑         │   │
│  └─────────────────────────┘   │
│                                 │
│  📸 참여작 둘러보기             │
│  ┌───┐ ┌───┐ ┌───┐ ┌───┐     │
│  │   │ │   │ │   │ │   │     │
│  └───┘ └───┘ └───┘ └───┘     │
│                                 │
├─────────────────────────────────┤
│      [🐾 나도 참여하기]          │
└─────────────────────────────────┘
```

#### 8.2.3 참여작 상세 (투표 화면)
```
┌─────────────────────────────────┐
│  ←  참여작 상세            [···]│
├─────────────────────────────────┤
│                                 │
│  ┌─────────────────────────┐   │
│  │                         │   │
│  │    [펫 사진 이미지]       │   │
│  │                         │   │
│  └─────────────────────────┘   │
│                                 │
│  🐱 나비                       │
│  @나비맘                       │
│                                 │
│  "완전 녹아버린 나비... 저게    │
│   고양이가 맞나요? ㅋㅋ"        │
│                                 │
│  ❤️ 1,523표  💬 48개 응원       │
│                                 │
│  ─────────────────────────────  │
│                                 │
│  💬 응원 메시지                 │
│  ┌─────────────────────────┐   │
│  │ 초코집사: 너무 귀여워요!   │   │
│  │ 뭉치아빠: 1위 응원해요 🎉 │   │
│  │ ...                      │   │
│  └─────────────────────────┘   │
│                                 │
│  [응원 메시지 남기기]           │
│                                 │
├─────────────────────────────────┤
│      [🗳️ 투표하기]              │
└─────────────────────────────────┘
```

---

## 9. 기술 스택

### 9.1 Backend

| 영역 | 기술 | 버전 | 선택 이유 |
|:---|:---|:---:|:---|
| Language | Java | 17 | LTS, 최신 기능 |
| Framework | Spring Boot | 3.2.x | 생산성, 생태계 |
| ORM | Spring Data JPA | - | 생산성, 추상화 |
| Database | MySQL | 8.0 | 실무 표준 |
| Cache | Redis | 7.x | 실시간 랭킹, 캐싱 |
| Security | Spring Security + JWT | - | 인증/인가 |
| Storage | AWS S3 | - | 이미지 저장 |
| Build | Gradle | 8.x | 빠른 빌드 |

### 9.2 Infra (선택적)

| 영역 | 기술 | 선택 이유 |
|:---|:---|:---|
| Server | AWS EC2 / Docker | 배포 환경 |
| CI/CD | GitHub Actions | 자동화 |
| Monitoring | Prometheus + Grafana | 성능 모니터링 |

### 9.3 Testing

| 영역 | 기술 | 용도 |
|:---|:---|:---|
| Unit Test | JUnit 5 + Mockito | 단위 테스트 |
| Load Test | Locust | 부하 테스트 |
| DB Test | H2 (Test) | 테스트 DB |

---

## 10. 보상 시스템

### 10.1 주간 1위 보상

```
🥇 1위 보상
├── 공식 SNS 소개 (인스타그램)
├── "이 주의 스타" 뱃지 (프로필 표시)
├── 협찬 상품 (사료, 간식, 장난감 등)
└── (선택) 유기동물 보호소 기부

🥈 2위 보상
├── "이 주의 인기스타" 뱃지
└── 협찬 상품 (소)

🥉 3위 보상
└── "이 주의 인기스타" 뱃지
```

### 10.2 참여 보상

```
모든 참여자:
├── 참여 뱃지 획득
├── 포인트 적립 (추후)
└── 추첨을 통한 상품 증정
```

### 10.3 비즈니스 모델 (확장 시)

```
수익 모델:
├── 반려동물 브랜드 광고/협찬
├── 프리미엄 기능 (투표권 추가 구매)
├── 굿즈 판매 (1위 펫 캘린더 등)
└── 제휴 (펫 보험, 병원, 호텔 등)
```

---

## 11. 성능 최적화 포인트

### 11.1 예상 병목 지점

```
🔴 Critical (반드시 최적화)
├── 투표 API 동시성 (중복 투표 방지)
├── 실시간 랭킹 조회 (매번 COUNT 금지)
└── 챌린지 마감 시 트래픽 폭주

🟡 Important (중요)
├── 참여작 목록 조회 (N+1 문제)
├── 이미지 로딩 최적화
└── 페이징 성능

🟢 Nice to have (있으면 좋음)
├── 응원 메시지 실시간 업데이트
└── 알림 시스템 비동기 처리
```

### 11.2 최적화 전략 (상세)

#### 11.2.1 투표 동시성 (Phase 4)
```
문제:
- 동시에 같은 entry에 투표 요청
- 중복 투표 발생 가능
- vote_count 정확성 보장 필요

해결:
1. DB Unique 제약조건 (entry_id, member_id)
2. 비관적 락 적용 (필요시)
3. Redis를 이용한 분산 락 (고트래픽 시)

이력서 표현:
"투표 기능에서 동시 요청 시 중복 데이터가 발생하는 문제를
 Unique 제약조건과 트랜잭션 처리로 해결,
 데이터 정합성 100% 보장"
```

#### 11.2.2 실시간 랭킹 (Phase 6)
```
문제:
- 매 요청마다 SELECT COUNT(*) → DB 부하
- 1,000명 동시 조회 시 서버 다운

해결:
1. Redis Sorted Set 활용
   - ZINCRBY로 투표 시 실시간 점수 증가
   - ZREVRANGE로 랭킹 조회 (O(log N))
2. vote_count 비동기 동기화

이력서 표현:
"실시간 랭킹 조회에서 매번 DB COUNT 쿼리로 인해
 응답 시간이 2초 이상 걸리던 문제를
 Redis Sorted Set으로 해결,
 응답 시간 2초 → 10ms (99.5% 개선)"
```

#### 11.2.3 N+1 문제 (Phase 3)
```
문제:
- 참여작 목록 조회 시
  - Entry 10개 조회 → 1 쿼리
  - 각 Entry의 Pet 조회 → 10 쿼리
  - 각 Entry의 Member 조회 → 10 쿼리
  - 총 21 쿼리!

해결:
1. Fetch Join 적용
2. @BatchSize 설정
3. DTO Projection

이력서 표현:
"참여작 목록 조회 시 N+1 문제로 21개 쿼리가 발생하는 현상을
 Fetch Join으로 해결,
 쿼리 수 21개 → 1개 (95% 감소),
 응답 시간 500ms → 50ms (90% 개선)"
```

#### 11.2.4 인덱스 최적화 (Phase 2)
```
문제:
- 챌린지별 랭킹 조회 시 Full Table Scan
- ORDER BY vote_count DESC → filesort 발생

해결:
1. 복합 인덱스 추가
   CREATE INDEX idx_entry_challenge_vote
   ON entry(challenge_id, vote_count DESC);

2. EXPLAIN으로 쿼리 플랜 분석

이력서 표현:
"랭킹 조회 쿼리에서 Full Table Scan이 발생하는 문제를
 복합 인덱스 적용으로 해결,
 쿼리 실행 시간 1.5초 → 30ms (98% 단축)"
```

### 11.3 최적화 로드맵

```
Phase 1: 부하테스트 환경 구축 (베이스라인 측정)
    ↓
Phase 2: DB 인덱스 최적화
    ↓
Phase 3: JPA/쿼리 최적화 (N+1 해결)
    ↓
Phase 4: 트랜잭션 & 동시성 제어
    ↓
Phase 5: 서버 코드 최적화
    ↓
Phase 6: Redis 캐싱 도입
    ↓
Phase 7: 최종 성능 측정 & 문서화
```

---

## 12. 확장 로드맵

### 12.1 Phase 2 기능

```
[포인트/미션 시스템]
- 미션 수행 → 포인트 획득
- 포인트로 투표권 교환
- 미션 예시: 앱 출석, 친구 초대, SNS 공유

[펫 프로필 페이지]
- 펫 전용 프로필 페이지
- 참여한 챌린지 히스토리
- 팔로워/팔로잉

[소셜 기능]
- 팔로우/팔로워
- 피드 (팔로우한 펫 활동)
```

### 12.2 Phase 3 기능

```
[알림 시스템]
- 푸시 알림 (FCM)
- 챌린지 시작/마감 알림
- 순위 변동 알림

[검색 기능]
- 펫 이름/종류 검색
- Elasticsearch 도입

[실시간 기능]
- WebSocket으로 실시간 랭킹
- 실시간 응원 메시지
```

### 12.3 Phase 4 기능

```
[커머스 연동]
- 협찬 상품 관리
- 당첨자 관리 시스템

[어드민 패널]
- 챌린지 관리
- 사용자 관리
- 통계 대시보드
```

---

## 📎 부록

### A. 용어 정의

| 용어 | 설명 |
|:---|:---|
| 집사 | 반려동물을 키우는 사용자 |
| 챌린지 | 주기적으로 진행되는 펫 대회 |
| 참여작 (Entry) | 챌린지에 제출된 펫 사진/영상 |
| 응원 메시지 | 참여작에 남기는 응원 댓글 |

### B. 챌린지 주제 예시

| 주차 | 주제 | 설명 |
|:---|:---|:---|
| 1주차 | 가장 늘어진 모습 | 소파, 바닥 어디든 늘어진 모습 |
| 2주차 | 웃긴 표정 대회 | 웃기거나 독특한 표정 |
| 3주차 | 주인과 닮은꼴 | 펫과 집사의 닮은 모습 |
| 4주차 | 잠자는 모습 | 귀여운 잠자는 포즈 |
| 5주차 | 간식 먹는 모습 | 맛있게 간식 먹는 순간 |
| 6주차 | 계절 패션쇼 | 옷 입은 펫 |
| 7주차 | 형제자매 함께 | 다른 펫과 함께 찍은 사진 |
| 8주차 | Before & After | 입양 초기 vs 현재 |

---

## 📝 문서 정보

| 항목 | 내용 |
|:---|:---|
| 버전 | 1.0.0 |
| 작성일 | 2025-01-30 |
| 작성자 | 김유찬 |
| 상태 | 초안 |

---

> **다음 단계**: 이 기획서를 바탕으로 리브랜딩 전략 및 최적화 로드맵 문서 작성
