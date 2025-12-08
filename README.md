# Leafiq

Leafiq  RESTful API 서버입니다.

## 기술 스택

### Backend
- **Java 21**
- **Spring Boot 3.5.6**
- **Spring Security** - JWT 기반 인증/인가
- **Spring Data JPA** - ORM
- **QueryDSL** - 동적 쿼리 생성

### Database
- **MySQL

### Storage
- **AWS S3** - 이미지 파일 저장

### Template Engine
- **Thymeleaf** - 약관 페이지 렌더링

### Build Tool
- **Gradle

## 주요 기능

### 인증 (Authentication)
- JWT 기반 로그인/로그아웃
- Access Token / Refresh Token
- 토큰 갱신

### 회원 (Member)
- 회원가입 (이메일, 비밀번호, 닉네임)
- 프로필 조회/수정
- 비밀번호 변경
- 회원 탈퇴

### 게시글 (Post)
- 게시글 작성/수정/삭제
- 게시글 목록 조회 (Cursor 기반 페이징)
- 게시글 상세 조회 (조회수 증가)
- 이미지 첨부 지원

### 댓글 (Comment)
- 댓글 작성/수정/삭제
- 댓글 목록 조회 (Cursor 기반 페이징)

### 좋아요 (Like)
- 게시글 좋아요 추가/삭제

### 이미지 (Image)
- S3 이미지 업로드 (최대 20MB)
- 이미지 삭제

### 약관
- 이용약관 페이지
- 개인정보처리방침 페이지

## 프로젝트 구조

```
src/main/java/org/example/kakaocommunity/
├── controller/          # REST API 컨트롤러
├── service/            # 비즈니스 로직
├── repository/         # JPA 리포지토리 (QueryDSL 포함)
├── entity/             # JPA 엔티티
├── dto/
│   ├── request/       # 요청 DTO
│   └── response/      # 응답 DTO
├── mapper/            # Entity ↔ DTO 변환
├── global/
│   ├── config/        # 설정 (Security, S3, QueryDSL, Web)
│   ├── security/      # JWT 필터, ArgumentResolver
│   ├── util/          # JWT 유틸리티
│   ├── validator/     # 비즈니스 검증
│   ├── exception/     # 예외 처리
│   └── apiPayload/    # 공통 응답 형식
└── KakaoCommunityApplication.java
```

## 시작하기

### 사전 요구사항
- JDK 21 이상
- MySQL 8.x
- AWS S3 버킷 (이미지 저장용)

## API 문서

자세한 API 명세는 
https://vagabond-marimba-cb6.notion.site/LEAFIQ-API-2c35de28245780d28336d4ad3ce752d1 를 참고하세요


### 주요 엔드포인트

#### 인증
- `POST /api/auth` - 로그인
- `DELETE /api/auth` - 로그아웃
- `POST /api/auth/refresh` - 토큰 갱신

#### 회원
- `POST /api/users` - 회원가입
- `GET /api/users/me` - 내 정보 조회
- `PATCH /api/users/me` - 프로필 수정
- `PATCH /api/users/me/password` - 비밀번호 변경
- `DELETE /api/users/me` - 회원 탈퇴

#### 게시글
- `POST /api/posts` - 게시글 작성
- `GET /api/posts` - 게시글 목록 조회 (커서 페이징)
- `GET /api/posts/{postId}` - 게시글 상세 조회
- `PATCH /api/posts/{postId}` - 게시글 수정
- `DELETE /api/posts/{postId}` - 게시글 삭제

#### 댓글
- `POST /api/posts/{postId}/comments` - 댓글 작성
- `GET /api/posts/{postId}/comments` - 댓글 목록 조회
- `PATCH /api/comments/{commentId}` - 댓글 수정
- `DELETE /api/comments/{commentId}` - 댓글 삭제

#### 좋아요
- `POST /api/posts/{postId}/likes` - 좋아요 추가
- `DELETE /api/posts/{postId}/likes` - 좋아요 삭제

#### 이미지
- `POST /api/images` - 이미지 업로드
- `DELETE /api/images/{imageId}` - 이미지 삭제

### 인증 방식

대부분의 API는 JWT 인증이 필요합니다. 요청 헤더에 다음과 같이 토큰을 포함해야 합니다:

```
Authorization: Bearer {accessToken}
```
