# PetStar 리브랜딩 전략

## 개요

기존 Kakao Community 프로젝트를 PetStar로 리브랜딩한다. 기존 코드 구조를 최대한 활용하면서 도메인만 변경한다.

---

## 엔티티 매핑

### 유지
| 기존 | 신규 | 변경사항 |
|:---|:---|:---|
| Member | Member | role, deletedAt 필드 추가 |
| Image | Image | 그대로 유지 |

### 변경
| 기존 | 신규 | 설명 |
|:---|:---|:---|
| Post | Entry | 챌린지 참여작 |
| Like | Vote | 투표 |
| Comment | SupportMessage | 응원 메시지 |

### 신규 추가
| 엔티티 | 설명 |
|:---|:---|
| Pet | 반려동물 정보 |
| Challenge | 챌린지 정보 |

---

## 필드 매핑

### Post → Entry
| 기존 (Post) | 신규 (Entry) | 변경 |
|:---|:---|:---|
| id | id | 유지 |
| memberId | memberId | 유지 |
| - | petId | **추가** |
| - | challengeId | **추가** |
| title | - | **삭제** |
| content | caption | 이름 변경 |
| postImageId | imageId | 이름 변경 |
| views | voteCount | 의미 변경 |
| createdAt | createdAt | 유지 |

### Like → Vote
| 기존 (Like) | 신규 (Vote) |
|:---|:---|
| id | id |
| postId | entryId |
| memberId | memberId |
| createdAt | createdAt |

### Comment → SupportMessage
| 기존 (Comment) | 신규 (SupportMessage) |
|:---|:---|
| id | id |
| postId | entryId |
| memberId | memberId |
| content | content |
| createdAt | createdAt |

---

## API 매핑

### 기존 → 신규
```
POST /auth              → POST /api/v1/auth/login
DELETE /auth            → POST /api/v1/auth/logout
POST /auth/refresh      → POST /api/v1/auth/refresh
POST /users             → POST /api/v1/auth/signup
GET /users/me           → GET /api/v1/members/me
PATCH /users/me         → PATCH /api/v1/members/me

POST /posts             → POST /api/v1/challenges/{id}/entries
GET /posts              → GET /api/v1/challenges/{id}/entries
GET /posts/{id}         → GET /api/v1/entries/{id}
DELETE /posts/{id}      → DELETE /api/v1/entries/{id}

POST /posts/{id}/likes    → POST /api/v1/entries/{id}/votes
DELETE /posts/{id}/likes  → DELETE /api/v1/entries/{id}/votes

POST /posts/{id}/comments   → POST /api/v1/entries/{id}/supports
GET /posts/{id}/comments    → GET /api/v1/entries/{id}/supports
DELETE /comments/{id}       → DELETE /api/v1/supports/{id}

POST /images            → POST /api/v1/images
```

### 신규 API
```
POST/GET/PATCH/DELETE /api/v1/pets         펫 관리
GET /api/v1/challenges                      챌린지 목록
GET /api/v1/challenges/{id}                 챌린지 상세
GET /api/v1/challenges/{id}/ranking         챌린지 랭킹
```

---

## 패키지 구조

### 변경 후
```
com.petstar
├── global/
│   ├── config/          (Security, JPA, Redis, S3)
│   ├── exception/       (GlobalExceptionHandler, ErrorCode)
│   ├── response/        (ApiResponse, ErrorResponse)
│   └── security/        (JWT 관련)
│
├── domain/
│   ├── auth/            (AuthController, AuthService)
│   ├── member/          (entity, repository, service, controller, dto)
│   ├── pet/             ⭐ 신규
│   ├── challenge/       ⭐ 신규
│   ├── entry/           ⭐ Post → Entry
│   ├── vote/            ⭐ Like → Vote
│   ├── support/         ⭐ Comment → Support
│   └── image/
│
└── infra/
    ├── s3/
    └── redis/           ⭐ 추후 추가
```

---

## DB 스키마 변경

### 테이블 이름 변경
```sql
ALTER TABLE post RENAME TO entry;
ALTER TABLE likes RENAME TO vote;
ALTER TABLE comment RENAME TO support_message;
```

### 신규 테이블
```sql
-- Pet 테이블
CREATE TABLE pet (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    species VARCHAR(20) NOT NULL,
    breed VARCHAR(50),
    birth_date DATE,
    gender VARCHAR(10),
    profile_image_id BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME,
    FOREIGN KEY (member_id) REFERENCES member(id)
);

-- Challenge 테이블
CREATE TABLE challenge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    description TEXT NOT NULL,
    thumbnail_id BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'UPCOMING',
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    max_entries INT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### Entry 테이블 수정
```sql
ALTER TABLE entry ADD COLUMN challenge_id BIGINT NOT NULL;
ALTER TABLE entry ADD COLUMN pet_id BIGINT NOT NULL;
ALTER TABLE entry CHANGE COLUMN content caption VARCHAR(200);
ALTER TABLE entry CHANGE COLUMN post_image_id image_id BIGINT;
ALTER TABLE entry CHANGE COLUMN views vote_count INT DEFAULT 0;

ALTER TABLE entry ADD CONSTRAINT uk_entry_challenge_pet UNIQUE (challenge_id, pet_id);
```

### Vote 테이블 수정
```sql
ALTER TABLE vote CHANGE COLUMN post_id entry_id BIGINT;
ALTER TABLE vote ADD CONSTRAINT uk_vote_entry_member UNIQUE (entry_id, member_id);
```

---

## 신규 Enum

```java
public enum Species {
    DOG, CAT, BIRD, FISH, RABBIT, HAMSTER, ETC
}

public enum Gender {
    MALE, FEMALE, UNKNOWN
}

public enum ChallengeStatus {
    UPCOMING, ACTIVE, ENDED
}

public enum Role {
    USER, ADMIN
}
```

---

## 작업 순서

1. 패키지 구조 변경 (com.petstar)
2. Enum 생성 (Species, Gender, ChallengeStatus)
3. 신규 엔티티 생성 (Pet, Challenge)
4. 기존 엔티티 수정 (Post→Entry, Like→Vote, Comment→SupportMessage)
5. Repository 수정/생성
6. Service 수정/생성
7. Controller 수정/생성 (API 경로 변경)
8. DTO 수정/생성
9. DB 마이그레이션
10. 테스트 코드 수정
