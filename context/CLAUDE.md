# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Leafiq is a Spring Boot REST API backend for a community platform with posts, comments, likes, and user management. It uses JWT authentication and AWS S3 for image storage.

## Build & Run Commands

```bash
# Build (excludes tests by default)
./gradlew clean build

# Run application
./gradlew bootRun

# Run JAR directly
java -jar build/libs/kakao-community-0.0.1-SNAPSHOT.jar

# Run tests
./gradlew test

# Build Docker image
docker build -t kakao-community .
```

## Technology Stack

- **Java 21** with **Spring Boot 3.5.6**
- **Spring Security** with JWT authentication (jjwt 0.12.3)
- **Spring Data JPA** + **QueryDSL 5.1.0** for database access
- **MySQL 9.4**
- **AWS S3** for image storage (ap-northeast-2 region)
- **Gradle 8.5**

## Architecture

### Layer Structure

```
controller/     → REST endpoints, request validation
service/        → Business logic, transactions
repository/     → JPA + QueryDSL for data access
entity/         → JPA entities with audit timestamps
dto/request/    → Input DTOs with validation annotations
dto/response/   → Output DTOs
mapper/         → Entity ↔ DTO conversions
```

### Key Patterns

**Authentication Flow:**
1. Login via `POST /auth` returns access + refresh tokens
2. `JwtAuthenticationFilter` extracts token from `Authorization: Bearer` header
3. `@LoginUser` annotation (resolved by `LoginUserArgumentResolver`) injects authenticated user ID into controller methods

**API Response Format:**
All endpoints return `ApiResponse<T>` wrapper:
```json
{
  "isSuccess": true,
  "code": 200,
  "message": "성공",
  "result": { /* data */ }
}
```

**Pagination:**
Uses cursor-based pagination (not offset). Query params: `cursorId` (last item ID), `limit` (page size). Implemented in `PostRepositoryImpl` and `CommentRepositoryImpl` using QueryDSL.

**Exception Handling:**
- `GeneralException` with `ErrorStatus` codes
- Global handler in `ExceptionAdvice` (`@RestControllerAdvice`)

### Domain Entities

- **Member** → User accounts (email, password with BCrypt, nickname, profile image)
- **Post** → User posts with title, content, image, like/view/comment counts
- **Comment** → Post comments
- **PostLike** → Like relationship between Member and Post
- **Image** → S3 file metadata (s3Key, url, status: USED/UNUSED/DELETED)
- **RefreshToken** → Stored in DB for logout/revocation

## Configuration

Environment variables for Docker deployment:
- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- `CLOUD_AWS_CREDENTIALS_ACCESS_KEY`, `CLOUD_AWS_CREDENTIALS_SECRET_KEY`
- `JWT_SECRET`

JWT expiration: Access token 25 min, Refresh token 7 days.

## API Endpoints

Authentication required except `/auth` (login), `/users` (signup), `/health`, `/terms`, `/privacy`.

- **Auth:** `POST /auth` (login), `DELETE /auth` (logout), `POST /auth/refresh`
- **Members:** `POST /users`, `GET/PATCH/DELETE /users/me`, `PATCH /users/me/password`
- **Posts:** CRUD at `/posts`, `/posts/{postId}`
- **Comments:** `/posts/{postId}/comments`, `/comments/{commentId}`
- **Likes:** `POST/DELETE /posts/{postId}/likes`
- **Images:** `POST /images` (multipart, max 20MB), `DELETE /images/{imageId}`
- **Health:** `GET /health`
- **Terms:** `GET /terms`, `GET /privacy` (Thymeleaf HTML pages)

See `API-SPEC.md` for full request/response details.

## Validation Rules

- **Email:** Valid email format
- **Password:** `^(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d@$!%*#?&]{8,}$` (letters + numbers, 8+ chars)
- **Nickname:** `^[가-힣a-zA-Z0-9]{2,10}$` (Korean/English/numbers, 2-10 chars)
