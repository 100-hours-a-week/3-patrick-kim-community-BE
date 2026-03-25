# PetStar API 명세서

## 기본 정보
- Base URL: `https://api.leafiq.site` (Production) / `http://localhost:8080` (Local)
- 인증 방식: JWT (Bearer Token)
- Content-Type: `application/json` (이미지 업로드 제외)
- API Prefix: 모든 API는 `/api` prefix가 자동으로 추가됨

## 목차

### 기존 커뮤니티 API
1. [인증 (Authentication)](#1-인증-authentication)
2. [회원 (Member)](#2-회원-member)
3. [게시글 (Post)](#3-게시글-post)
4. [댓글 (Comment)](#4-댓글-comment)
5. [이미지 (Image)](#5-이미지-image)
6. [좋아요 (Like)](#6-좋아요-like)

### PetStar API (v1)
7. [챌린지 (Challenge)](#7-챌린지-challenge)
8. [참여작 (Entry)](#8-참여작-entry)
9. [투표 (Vote)](#9-투표-vote)
10. [응원 메시지 (Support)](#10-응원-메시지-support)
11. [펫 (Pet)](#11-펫-pet)

### 기타
12. [헬스체크 (Health)](#12-헬스체크-health)
13. [약관 페이지 (Terms)](#13-약관-페이지-terms)

---

## 1. 인증 (Authentication)

### 1.1 로그인
```
POST /api/auth
```

**Request Body**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
}
```

### 1.2 로그아웃
```
DELETE /api/auth
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (204 No Content)**

### 1.3 토큰 갱신
```
POST /api/auth/refresh
```

**Request Body**
```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
}
```

---

## 2. 회원 (Member)

### 2.1 회원가입
```
POST /api/users
```

**Request Body**
```json
{
  "email": "user@example.com",
  "password": "password123!",
  "nickname": "홍길동",
  "profileImageId": 123
}
```

| 필드 | 타입 | 필수 | 설명 |
|:-----|:-----|:-----|:-----|
| email | string | O | 이메일 형식 |
| password | string | O | 영문+숫자 조합 8자 이상 |
| nickname | string | O | 한글/영문/숫자 2-10자 |
| profileImageId | number | X | 프로필 이미지 ID |

**Response (201 Created)**
```json
{
  "isSuccess": true,
  "code": 201,
  "message": "성공",
  "result": {
    "userId": 1
  }
}
```

### 2.2 회원 정보 조회
```
GET /api/users/me
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "id": 1,
    "email": "user@example.com",
    "nickname": "홍길동",
    "profileImageUrl": "https://s3.amazonaws.com/...",
    "createdAt": "2025-12-04T10:30:00"
  }
}
```

### 2.3 비밀번호 변경
```
PATCH /api/users/me/password
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Request Body**
```json
{
  "currentPassword": "oldPassword123!",
  "newPassword": "newPassword456!"
}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": "비밀번호가 변경되었습니다."
}
```

### 2.4 프로필 수정
```
PATCH /api/users/me
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Request Body**
```json
{
  "nickname": "새닉네임",
  "profileImageId": 456
}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "id": 1,
    "nickname": "새닉네임",
    "profileImageUrl": "https://s3.amazonaws.com/...",
    "updatedAt": "2025-12-04T11:00:00"
  }
}
```

### 2.5 회원 탈퇴
```
DELETE /api/users/me
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": "회원 탈퇴가 완료되었습니다."
}
```

---

## 3. 게시글 (Post)

### 3.1 게시글 작성
```
POST /api/posts
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Request Body**
```json
{
  "title": "게시글 제목",
  "content": "게시글 내용",
  "postImageId": 123
}
```

**Response (201 Created)**
```json
{
  "isSuccess": true,
  "code": 201,
  "message": "성공",
  "result": {
    "postId": 1
  }
}
```

### 3.2 게시글 목록 조회 (Cursor 기반 페이징)
```
GET /api/posts?cursorId={cursorId}&limit={limit}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Query Parameters**
| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|:---------|:-----|:-----|:-------|:-----|
| cursorId | number | X | - | 마지막 게시글 ID |
| limit | number | X | 10 | 조회할 게시글 수 |

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "posts": [
      {
        "member": {
          "id": 1,
          "nickname": "홍길동",
          "profileImageUrl": "https://s3.amazonaws.com/..."
        },
        "postId": 10,
        "title": "게시글 제목",
        "createdAt": "2025-12-04T10:00:00",
        "postImageUrl": "https://s3.amazonaws.com/...",
        "likes": 15,
        "comments": 3,
        "views": 100
      }
    ],
    "nextCursorId": 9,
    "hasNext": true
  }
}
```

### 3.3 게시글 상세 조회
```
GET /api/posts/{postId}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "user": {
      "id": 1,
      "nickname": "홍길동",
      "profileImageUrl": "https://s3.amazonaws.com/..."
    },
    "postId": 1,
    "title": "게시글 제목",
    "content": "게시글 내용",
    "createdAt": "2025-12-04T10:00:00",
    "postImageUrl": "https://s3.amazonaws.com/...",
    "liked": true,
    "likes": 15,
    "comments": 3,
    "views": 100
  }
}
```

### 3.4 게시글 수정
```
PATCH /api/posts/{postId}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Request Body**
```json
{
  "title": "수정된 제목",
  "content": "수정된 내용",
  "postImageId": 456
}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "postId": 1,
    "title": "수정된 제목",
    "content": "수정된 내용",
    "updatedAt": "2025-12-04T11:00:00",
    "postImageUrl": "https://s3.amazonaws.com/..."
  }
}
```

### 3.5 게시글 삭제
```
DELETE /api/posts/{postId}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": "게시글을 성공적으로 삭제했습니다."
}
```

---

## 4. 댓글 (Comment)

### 4.1 댓글 작성
```
POST /api/posts/{postId}/comments
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Request Body**
```json
{
  "content": "댓글 내용"
}
```

**Response (201 Created)**
```json
{
  "isSuccess": true,
  "code": 201,
  "message": "성공",
  "result": {
    "commentId": 1
  }
}
```

### 4.2 댓글 목록 조회 (Cursor 기반 페이징)
```
GET /api/posts/{postId}/comments?cursorId={cursorId}&limit={limit}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Query Parameters**
| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|:---------|:-----|:-----|:-------|:-----|
| cursorId | number | X | - | 마지막 댓글 ID |
| limit | number | X | 10 | 조회할 댓글 수 |

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "comments": [
      {
        "user": {
          "id": 1,
          "nickname": "홍길동",
          "profileImageUrl": "https://s3.amazonaws.com/..."
        },
        "commentId": 1,
        "content": "댓글 내용"
      }
    ],
    "nextCursorId": 0,
    "hasNext": false
  }
}
```

### 4.3 댓글 수정
```
PATCH /api/comments/{commentId}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Request Body**
```json
{
  "content": "수정된 댓글 내용"
}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": "댓글을 수정했습니다."
}
```

### 4.4 댓글 삭제
```
DELETE /api/comments/{commentId}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": "성공적으로 삭제했습니다."
}
```

---

## 5. 이미지 (Image)

### 5.1 이미지 업로드
```
POST /api/images
```

**Headers**
```
Content-Type: multipart/form-data
```

**Request Body (multipart/form-data)**
| 필드 | 타입 | 필수 | 설명 |
|:-----|:-----|:-----|:-----|
| image | file | O | 이미지 파일 (최대 20MB) |

**Response (201 Created)**
```json
{
  "isSuccess": true,
  "code": 201,
  "message": "성공",
  "result": {
    "imageId": 1,
    "imageUrl": "https://s3.amazonaws.com/petstar-bucket/..."
  }
}
```

### 5.2 이미지 삭제
```
DELETE /api/images/{imageId}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": "이미지가 삭제되었습니다."
}
```

---

## 6. 좋아요 (Like)

### 6.1 좋아요 추가
```
POST /api/posts/{postId}/likes
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": "좋아요 추가했습니다."
}
```

### 6.2 좋아요 삭제
```
DELETE /api/posts/{postId}/likes
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": "좋아요 삭제했습니다."
}
```

---

## 7. 챌린지 (Challenge)

> PetStar의 핵심 기능. 펫 사진 챌린지를 관리합니다.

### 7.1 챌린지 목록 조회
```
GET /api/v1/challenges?status={status}
```

**Query Parameters**
| 파라미터 | 타입 | 필수 | 설명 |
|:---------|:-----|:-----|:-----|
| status | string | X | 챌린지 상태 (UPCOMING, ACTIVE, ENDED) |

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": [
    {
      "challengeId": 1,
      "title": "2024 귀여운 강아지 챌린지",
      "thumbnailUrl": "https://s3.amazonaws.com/...",
      "status": "ACTIVE",
      "startAt": "2024-01-01T00:00:00",
      "endAt": "2024-01-31T23:59:59"
    }
  ]
}
```

### 7.2 챌린지 상세 조회
```
GET /api/v1/challenges/{challengeId}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "challengeId": 1,
    "title": "2024 귀여운 강아지 챌린지",
    "description": "가장 귀여운 강아지 사진을 올려주세요!",
    "thumbnailUrl": "https://s3.amazonaws.com/...",
    "status": "ACTIVE",
    "startAt": "2024-01-01T00:00:00",
    "endAt": "2024-01-31T23:59:59",
    "maxEntries": 1000
  }
}
```

### 7.3 챌린지 랭킹 조회
```
GET /api/v1/challenges/{challengeId}/ranking?limit={limit}
```

**Query Parameters**
| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|:---------|:-----|:-----|:-------|:-----|
| limit | number | X | 10 | 조회할 랭킹 수 |

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": [
    {
      "rank": 1,
      "entryId": 42,
      "petName": "초코",
      "imageUrl": "https://s3.amazonaws.com/...",
      "voteCount": 1523
    },
    {
      "rank": 2,
      "entryId": 15,
      "petName": "뽀삐",
      "imageUrl": "https://s3.amazonaws.com/...",
      "voteCount": 1201
    }
  ]
}
```

### 7.4 챌린지 참여작 목록 조회 (Cursor 기반 페이징)
```
GET /api/v1/challenges/{challengeId}/entries?cursorId={cursorId}&limit={limit}
```

**Query Parameters**
| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|:---------|:-----|:-----|:-------|:-----|
| cursorId | number | X | - | 마지막 참여작 ID |
| limit | number | X | 10 | 조회할 참여작 수 |

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "entries": [
      {
        "entryId": 1,
        "petName": "초코",
        "imageUrl": "https://s3.amazonaws.com/...",
        "voteCount": 150,
        "createdAt": "2024-01-15T10:30:00"
      }
    ],
    "nextCursorId": 10,
    "hasNext": true
  }
}
```

### 7.5 챌린지 참여 (참여작 등록)
```
POST /api/v1/challenges/{challengeId}/entries
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Request Body**
```json
{
  "petId": 1,
  "imageId": 123,
  "caption": "우리 초코의 귀여운 모습!"
}
```

| 필드 | 타입 | 필수 | 설명 |
|:-----|:-----|:-----|:-----|
| petId | number | O | 참여할 펫 ID |
| imageId | number | O | 참여 이미지 ID |
| caption | string | X | 참여작 설명 |

**Response (201 Created)**
```json
{
  "isSuccess": true,
  "code": 201,
  "message": "성공",
  "result": {
    "entryId": 1
  }
}
```

---

## 8. 참여작 (Entry)

### 8.1 참여작 상세 조회
```
GET /api/v1/entries/{entryId}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "entryId": 1,
    "challengeId": 1,
    "challengeTitle": "2024 귀여운 강아지 챌린지",
    "petId": 5,
    "petName": "초코",
    "ownerNickname": "홍길동",
    "imageUrl": "https://s3.amazonaws.com/...",
    "caption": "우리 초코의 귀여운 모습!",
    "voteCount": 150,
    "voted": false,
    "createdAt": "2024-01-15T10:30:00"
  }
}
```

### 8.2 참여작 삭제
```
DELETE /api/v1/entries/{entryId}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": "참여작을 삭제했습니다."
}
```

---

## 9. 투표 (Vote)

### 9.1 투표하기
```
POST /api/v1/entries/{entryId}/votes
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (201 Created)**
```json
{
  "isSuccess": true,
  "code": 201,
  "message": "성공",
  "result": {
    "entryId": 1,
    "voteCount": 151
  }
}
```

**Error Response (409 Conflict - 중복 투표)**
```json
{
  "isSuccess": false,
  "code": 409,
  "message": "이미 투표한 참여작입니다.",
  "result": null
}
```

### 9.2 투표 취소
```
DELETE /api/v1/entries/{entryId}/votes
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "entryId": 1,
    "voteCount": 150
  }
}
```

---

## 10. 응원 메시지 (Support)

### 10.1 응원 메시지 작성
```
POST /api/v1/entries/{entryId}/supports
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Request Body**
```json
{
  "content": "초코 너무 귀여워요! 응원합니다!"
}
```

**Response (201 Created)**
```json
{
  "isSuccess": true,
  "code": 201,
  "message": "성공",
  "result": {
    "messageId": 1
  }
}
```

### 10.2 응원 메시지 목록 조회 (Cursor 기반 페이징)
```
GET /api/v1/entries/{entryId}/supports?cursorId={cursorId}&limit={limit}
```

**Query Parameters**
| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|:---------|:-----|:-----|:-------|:-----|
| cursorId | number | X | - | 마지막 메시지 ID |
| limit | number | X | 10 | 조회할 메시지 수 |

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "messages": [
      {
        "messageId": 1,
        "authorNickname": "김철수",
        "authorProfileImageUrl": "https://s3.amazonaws.com/...",
        "content": "초코 너무 귀여워요! 응원합니다!",
        "createdAt": "2024-01-15T11:00:00"
      }
    ],
    "nextCursorId": 5,
    "hasNext": true
  }
}
```

### 10.3 응원 메시지 삭제
```
DELETE /api/v1/supports/{messageId}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": "응원 메시지를 삭제했습니다."
}
```

---

## 11. 펫 (Pet)

### 11.1 펫 등록
```
POST /api/v1/pets
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Request Body**
```json
{
  "name": "초코",
  "species": "DOG",
  "breed": "말티즈",
  "birthDate": "2020-05-15",
  "gender": "MALE",
  "profileImageId": 123
}
```

| 필드 | 타입 | 필수 | 설명 |
|:-----|:-----|:-----|:-----|
| name | string | O | 펫 이름 |
| species | string | O | 종류 (DOG, CAT, BIRD, FISH, RABBIT, HAMSTER, ETC) |
| breed | string | X | 품종 |
| birthDate | string | X | 생년월일 (YYYY-MM-DD) |
| gender | string | X | 성별 (MALE, FEMALE, UNKNOWN) |
| profileImageId | number | X | 프로필 이미지 ID |

**Response (201 Created)**
```json
{
  "isSuccess": true,
  "code": 201,
  "message": "성공",
  "result": {
    "petId": 1
  }
}
```

### 11.2 내 펫 목록 조회
```
GET /api/v1/pets
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": [
    {
      "petId": 1,
      "name": "초코",
      "species": "DOG",
      "profileImageUrl": "https://s3.amazonaws.com/..."
    },
    {
      "petId": 2,
      "name": "나비",
      "species": "CAT",
      "profileImageUrl": "https://s3.amazonaws.com/..."
    }
  ]
}
```

### 11.3 펫 상세 조회
```
GET /api/v1/pets/{petId}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "petId": 1,
    "name": "초코",
    "species": "DOG",
    "breed": "말티즈",
    "birthDate": "2020-05-15",
    "gender": "MALE",
    "profileImageUrl": "https://s3.amazonaws.com/..."
  }
}
```

### 11.4 펫 정보 수정
```
PATCH /api/v1/pets/{petId}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Request Body**
```json
{
  "name": "초코초코",
  "breed": "말티즈 믹스"
}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    "petId": 1
  }
}
```

### 11.5 펫 삭제
```
DELETE /api/v1/pets/{petId}
```

**Headers**
```
Authorization: Bearer {accessToken}
```

**Response (200 OK)**
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": "펫을 삭제했습니다."
}
```

---

## 12. 헬스체크 (Health)

### 12.1 헬스체크
```
GET /api/health
```

**Response (200 OK)**
```
OK
```

---

## 13. 약관 페이지 (Terms)

### 13.1 이용약관 페이지
```
GET /api/terms
```

**Response**: HTML 페이지 (Thymeleaf 템플릿)

### 13.2 개인정보처리방침 페이지
```
GET /api/privacy
```

**Response**: HTML 페이지 (Thymeleaf 템플릿)

---

## 공통 응답 형식

### 성공 응답
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": {
    // 응답 데이터
  }
}
```

### 에러 응답
```json
{
  "isSuccess": false,
  "code": 400,
  "message": "에러 메시지",
  "result": null
}
```

### HTTP 상태 코드
| 코드 | 설명 |
|:-----|:-----|
| 200 | 성공 |
| 201 | 생성 성공 |
| 204 | 성공 (응답 본문 없음) |
| 400 | 잘못된 요청 |
| 401 | 인증 필요 |
| 403 | 권한 없음 |
| 404 | 리소스 없음 |
| 409 | 충돌 (중복 등) |
| 500 | 서버 에러 |

---

## 인증 헤더

대부분의 API는 JWT 인증이 필요합니다:

```
Authorization: Bearer {accessToken}
```

**인증 불필요 API (Public)**
- `POST /api/auth` (로그인)
- `POST /api/users` (회원가입)
- `GET /api/health` (헬스체크)
- `GET /api/terms`, `GET /api/privacy` (약관)
- `GET /api/v1/challenges/**` (챌린지 조회)
- `GET /api/v1/entries/{id}/supports` (응원 메시지 조회)

---

## Enum 타입

### Species (펫 종류)
| 값 | 설명 |
|:---|:-----|
| DOG | 강아지 |
| CAT | 고양이 |
| BIRD | 새 |
| FISH | 물고기 |
| RABBIT | 토끼 |
| HAMSTER | 햄스터 |
| ETC | 기타 |

### Gender (성별)
| 값 | 설명 |
|:---|:-----|
| MALE | 수컷 |
| FEMALE | 암컷 |
| UNKNOWN | 알 수 없음 |

### ChallengeStatus (챌린지 상태)
| 값 | 설명 |
|:---|:-----|
| UPCOMING | 예정 |
| ACTIVE | 진행중 |
| ENDED | 종료 |

---

## 유효성 검사 규칙

### 이메일
- 형식: 유효한 이메일 형식
- 예시: `user@example.com`

### 비밀번호
- 패턴: `^(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d@$!%*#?&]{8,}$`
- 조건: 영문, 숫자 조합 8자 이상
- 특수문자 허용: `@$!%*#?&`

### 닉네임
- 패턴: `^[가-힣a-zA-Z0-9]{2,10}$`
- 조건: 한글, 영문, 숫자 조합 2-10자

---

## 파일 업로드 제한

- 최대 파일 크기: 20MB
- 최대 요청 크기: 20MB
- 지원 형식: JPG, PNG, GIF, WebP

---

## JWT 토큰 만료 시간

- Access Token: 25분 (1,500,000ms)
- Refresh Token: 7일 (604,800,000ms)
