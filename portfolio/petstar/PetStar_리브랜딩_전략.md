# 🔄 PetStar 리브랜딩 전략

> Kakao Community → PetStar 전환 가이드

---

## 📋 목차

1. [리브랜딩 개요](#1-리브랜딩-개요)
2. [현재 구조 분석](#2-현재-구조-분석)
3. [변경 매핑 테이블](#3-변경-매핑-테이블)
4. [엔티티 변경 상세](#4-엔티티-변경-상세)
5. [API 변경 상세](#5-api-변경-상세)
6. [신규 추가 항목](#6-신규-추가-항목)
7. [패키지 구조 변경](#7-패키지-구조-변경)
8. [DB 스키마 마이그레이션](#8-db-스키마-마이그레이션)
9. [단계별 작업 계획](#9-단계별-작업-계획)
10. [체크리스트](#10-체크리스트)

---

## 1. 리브랜딩 개요

### 1.1 목적

```
기존 Kakao Community 프로젝트의 코드 구조를 최대한 활용하면서,
PetStar라는 새로운 도메인으로 전환하여 포트폴리오 가치를 높인다.
```

### 1.2 리브랜딩 원칙

| 원칙 | 설명 |
|:---|:---|
| **최소 변경** | 기존 코드 구조 최대한 유지 |
| **명확한 매핑** | 1:1 대응 관계 유지 |
| **점진적 전환** | 한 번에 하나씩 변경 |
| **기능 보존** | 기존 기능 100% 유지 후 확장 |

### 1.3 변경 범위

```
✅ 변경 대상
├── 엔티티 이름 및 필드
├── API 엔드포인트
├── DTO 클래스
├── 서비스/레포지토리 이름
├── 패키지 구조
└── DB 테이블/컬럼명

❌ 유지 대상
├── 비즈니스 로직 흐름
├── 인증/인가 구조
├── 예외 처리 방식
├── 응답 형식
└── 기술 스택
```

---

## 2. 현재 구조 분석

### 2.1 기존 엔티티 구조

```
Kakao Community 엔티티:

┌─────────────────┐
│     Member      │
├─────────────────┤
│ id              │
│ email           │
│ password        │
│ nickname        │
│ profileImageId  │
│ createdAt       │
└────────┬────────┘
         │
         │ 1:N
         ▼
┌─────────────────┐      ┌─────────────────┐
│      Post       │      │      Image      │
├─────────────────┤      ├─────────────────┤
│ id              │      │ id              │
│ memberId (FK)   │      │ url             │
│ title           │      │ originalName    │
│ content         │      │ storedName      │
│ postImageId     │      │ size            │
│ views           │      │ contentType     │
│ createdAt       │      └─────────────────┘
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌────────┐  ┌─────────┐
│  Like  │  │ Comment │
├────────┤  ├─────────┤
│ id     │  │ id      │
│ postId │  │ postId  │
│ memberId│ │ memberId│
│ createdAt│ │ content │
└────────┘  │ createdAt│
            └─────────┘
```

### 2.2 기존 API 구조

```
현재 API 엔드포인트:

[인증]
POST   /auth              로그인
DELETE /auth              로그아웃
POST   /auth/refresh      토큰 갱신

[회원]
POST   /users             회원가입
GET    /users/me          내 정보 조회
PATCH  /users/me          내 정보 수정
PATCH  /users/me/password 비밀번호 변경
DELETE /users/me          회원 탈퇴

[게시글]
POST   /posts             게시글 작성
GET    /posts             게시글 목록 조회
GET    /posts/{postId}    게시글 상세 조회
PATCH  /posts/{postId}    게시글 수정
DELETE /posts/{postId}    게시글 삭제

[좋아요]
POST   /posts/{postId}/likes    좋아요 추가
DELETE /posts/{postId}/likes    좋아요 삭제

[댓글]
POST   /posts/{postId}/comments      댓글 작성
GET    /posts/{postId}/comments      댓글 목록 조회
PATCH  /comments/{commentId}         댓글 수정
DELETE /comments/{commentId}         댓글 삭제

[이미지]
POST   /images            이미지 업로드
DELETE /images/{imageId}  이미지 삭제
```

---

## 3. 변경 매핑 테이블

### 3.1 엔티티 매핑

| 기존 (Kakao) | 신규 (PetStar) | 변경 유형 | 비고 |
|:---|:---|:---:|:---|
| `Member` | `Member` | 유지 | 필드 추가 |
| - | `Pet` | **신규** | 반려동물 정보 |
| - | `Challenge` | **신규** | 챌린지 정보 |
| `Post` | `Entry` | **변경** | 챌린지 참여작 |
| `Like` | `Vote` | **변경** | 투표 |
| `Comment` | `SupportMessage` | **변경** | 응원 메시지 |
| `Image` | `Image` | 유지 | 그대로 사용 |

### 3.2 필드 매핑 (Post → Entry)

| 기존 (Post) | 신규 (Entry) | 변경 내용 |
|:---|:---|:---|
| `id` | `id` | 유지 |
| `memberId` | `memberId` | 유지 |
| - | `petId` | **추가** (FK) |
| - | `challengeId` | **추가** (FK) |
| `title` | - | **삭제** |
| `content` | `caption` | **이름 변경** |
| `postImageId` | `imageId` | **이름 변경** |
| `views` | `voteCount` | **의미 변경** |
| `createdAt` | `createdAt` | 유지 |
| `updatedAt` | `updatedAt` | 유지 |

### 3.3 필드 매핑 (Like → Vote)

| 기존 (Like) | 신규 (Vote) | 변경 내용 |
|:---|:---|:---|
| `id` | `id` | 유지 |
| `postId` | `entryId` | **이름 변경** |
| `memberId` | `memberId` | 유지 |
| `createdAt` | `createdAt` | 유지 |

### 3.4 필드 매핑 (Comment → SupportMessage)

| 기존 (Comment) | 신규 (SupportMessage) | 변경 내용 |
|:---|:---|:---|
| `id` | `id` | 유지 |
| `postId` | `entryId` | **이름 변경** |
| `memberId` | `memberId` | 유지 |
| `content` | `content` | 유지 |
| `createdAt` | `createdAt` | 유지 |

### 3.5 API 엔드포인트 매핑

| 기존 API | 신규 API | 비고 |
|:---|:---|:---|
| `POST /auth` | `POST /api/v1/auth/login` | 경로 변경 |
| `DELETE /auth` | `POST /api/v1/auth/logout` | 경로 변경 |
| `POST /auth/refresh` | `POST /api/v1/auth/refresh` | 경로 변경 |
| `POST /users` | `POST /api/v1/auth/signup` | 경로 변경 |
| `GET /users/me` | `GET /api/v1/members/me` | 경로 변경 |
| `PATCH /users/me` | `PATCH /api/v1/members/me` | 경로 변경 |
| - | `POST /api/v1/pets` | **신규** |
| - | `GET /api/v1/pets` | **신규** |
| - | `GET /api/v1/challenges` | **신규** |
| - | `GET /api/v1/challenges/{id}` | **신규** |
| `POST /posts` | `POST /api/v1/challenges/{id}/entries` | **변경** |
| `GET /posts` | `GET /api/v1/challenges/{id}/entries` | **변경** |
| `GET /posts/{id}` | `GET /api/v1/entries/{id}` | **변경** |
| `DELETE /posts/{id}` | `DELETE /api/v1/entries/{id}` | **변경** |
| `POST /posts/{id}/likes` | `POST /api/v1/entries/{id}/votes` | **변경** |
| `DELETE /posts/{id}/likes` | `DELETE /api/v1/entries/{id}/votes` | **변경** |
| `POST /posts/{id}/comments` | `POST /api/v1/entries/{id}/supports` | **변경** |
| `GET /posts/{id}/comments` | `GET /api/v1/entries/{id}/supports` | **변경** |
| `DELETE /comments/{id}` | `DELETE /api/v1/supports/{id}` | **변경** |
| `POST /images` | `POST /api/v1/images` | 경로만 변경 |
| - | `GET /api/v1/challenges/{id}/ranking` | **신규** |

---

## 4. 엔티티 변경 상세

### 4.1 Member (수정)

```java
// 기존
@Entity
public class Member {
    @Id @GeneratedValue
    private Long id;
    
    private String email;
    private String password;
    private String nickname;
    
    @ManyToOne(fetch = FetchType.LAZY)
    private Image profileImage;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

// 변경 후
@Entity
@Table(name = "member")
public class Member {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private String email;
    
    @Column(nullable = false)
    private String password;
    
    @Column(nullable = false, length = 20)
    private String nickname;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_image_id")
    private Image profileImage;
    
    @Enumerated(EnumType.STRING)
    private Role role = Role.USER;  // 추가
    
    @OneToMany(mappedBy = "member")
    private List<Pet> pets = new ArrayList<>();  // 추가
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;  // 추가 (Soft Delete)
}
```

### 4.2 Pet (신규)

```java
@Entity
@Table(name = "pet")
public class Pet {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;
    
    @Column(nullable = false, length = 50)
    private String name;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Species species;  // DOG, CAT, BIRD, FISH, ETC
    
    @Column(length = 50)
    private String breed;  // 품종
    
    private LocalDate birthDate;
    
    @Enumerated(EnumType.STRING)
    private Gender gender;  // MALE, FEMALE, UNKNOWN
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_image_id")
    private Image profileImage;
    
    @Column(length = 200)
    private String introduction;
    
    @OneToMany(mappedBy = "pet")
    private List<Entry> entries = new ArrayList<>();
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}

public enum Species {
    DOG, CAT, BIRD, FISH, RABBIT, HAMSTER, ETC
}

public enum Gender {
    MALE, FEMALE, UNKNOWN
}
```

### 4.3 Challenge (신규)

```java
@Entity
@Table(name = "challenge")
public class Challenge {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 100)
    private String title;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thumbnail_id")
    private Image thumbnail;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChallengeStatus status;  // UPCOMING, ACTIVE, ENDED
    
    @Column(nullable = false)
    private LocalDateTime startAt;
    
    @Column(nullable = false)
    private LocalDateTime endAt;
    
    private Integer maxEntries;  // null = 무제한
    
    @OneToMany(mappedBy = "challenge")
    private List<Entry> entries = new ArrayList<>();
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // 비즈니스 메서드
    public boolean isActive() {
        LocalDateTime now = LocalDateTime.now();
        return status == ChallengeStatus.ACTIVE 
            && now.isAfter(startAt) 
            && now.isBefore(endAt);
    }
    
    public boolean canParticipate() {
        if (!isActive()) return false;
        if (maxEntries == null) return true;
        return entries.size() < maxEntries;
    }
}

public enum ChallengeStatus {
    UPCOMING,  // 예정
    ACTIVE,    // 진행중
    ENDED      // 종료
}
```

### 4.4 Entry (Post 변경)

```java
// 기존: Post
@Entity
public class Post {
    private Long id;
    private Member member;
    private String title;
    private String content;
    private Image postImage;
    private int views;
    private LocalDateTime createdAt;
}

// 변경 후: Entry
@Entity
@Table(name = "entry", 
       uniqueConstraints = @UniqueConstraint(
           columnNames = {"challenge_id", "pet_id"}
       ))
public class Entry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "challenge_id", nullable = false)
    private Challenge challenge;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pet_id", nullable = false)
    private Pet pet;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_id", nullable = false)
    private Image image;
    
    @Column(length = 200)
    private String caption;  // 기존 content
    
    @Column(nullable = false)
    private Integer voteCount = 0;  // 기존 views
    
    @OneToMany(mappedBy = "entry")
    private List<Vote> votes = new ArrayList<>();
    
    @OneToMany(mappedBy = "entry")
    private List<SupportMessage> supportMessages = new ArrayList<>();
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
    
    // 투표수 증가 (동시성 고려 - 별도 쿼리로 처리)
    public void incrementVoteCount() {
        this.voteCount++;
    }
}
```

### 4.5 Vote (Like 변경)

```java
// 기존: Like
@Entity
public class Like {
    private Long id;
    private Post post;
    private Member member;
    private LocalDateTime createdAt;
}

// 변경 후: Vote
@Entity
@Table(name = "vote",
       uniqueConstraints = @UniqueConstraint(
           columnNames = {"entry_id", "member_id"}
       ))
public class Vote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id", nullable = false)
    private Entry entry;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;
    
    private LocalDateTime createdAt;
    
    // 생성자에서 검증
    public Vote(Entry entry, Member member) {
        validateVote(entry, member);
        this.entry = entry;
        this.member = member;
        this.createdAt = LocalDateTime.now();
    }
    
    private void validateVote(Entry entry, Member member) {
        // 본인 참여작에 투표 불가
        if (entry.getMember().getId().equals(member.getId())) {
            throw new IllegalArgumentException("본인 참여작에는 투표할 수 없습니다");
        }
    }
}
```

### 4.6 SupportMessage (Comment 변경)

```java
// 기존: Comment
@Entity
public class Comment {
    private Long id;
    private Post post;
    private Member member;
    private String content;
    private LocalDateTime createdAt;
}

// 변경 후: SupportMessage
@Entity
@Table(name = "support_message")
public class SupportMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id", nullable = false)
    private Entry entry;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;
    
    @Column(nullable = false, length = 300)
    private String content;
    
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
}
```

---

## 5. API 변경 상세

### 5.1 인증 API

```
[기존]
POST   /auth              → POST   /api/v1/auth/login
DELETE /auth              → POST   /api/v1/auth/logout
POST   /auth/refresh      → POST   /api/v1/auth/refresh
POST   /users             → POST   /api/v1/auth/signup

[변경 사항]
- API 버전 prefix 추가: /api/v1
- RESTful 네이밍 개선
- 회원가입을 auth 하위로 이동
```

### 5.2 회원 API

```
[기존]
GET    /users/me          → GET    /api/v1/members/me
PATCH  /users/me          → PATCH  /api/v1/members/me
PATCH  /users/me/password → PATCH  /api/v1/members/me/password
DELETE /users/me          → DELETE /api/v1/members/me

[변경 사항]
- users → members로 네이밍 변경
- API 버전 prefix 추가
```

### 5.3 펫 API (신규)

```
[신규]
POST   /api/v1/pets                 펫 등록
GET    /api/v1/pets                 내 펫 목록 조회
GET    /api/v1/pets/{petId}         펫 상세 조회
PATCH  /api/v1/pets/{petId}         펫 정보 수정
DELETE /api/v1/pets/{petId}         펫 삭제
```

### 5.4 챌린지 API (신규)

```
[신규]
GET    /api/v1/challenges                      챌린지 목록 조회
GET    /api/v1/challenges/{challengeId}        챌린지 상세 조회
GET    /api/v1/challenges/{challengeId}/ranking 챌린지 랭킹 조회

[관리자용 - 추후]
POST   /api/v1/admin/challenges                챌린지 생성
PATCH  /api/v1/admin/challenges/{id}           챌린지 수정
DELETE /api/v1/admin/challenges/{id}           챌린지 삭제
```

### 5.5 참여작 API (Post → Entry)

```
[기존 → 변경]
POST   /posts             → POST   /api/v1/challenges/{challengeId}/entries
GET    /posts             → GET    /api/v1/challenges/{challengeId}/entries
GET    /posts/{postId}    → GET    /api/v1/entries/{entryId}
DELETE /posts/{postId}    → DELETE /api/v1/entries/{entryId}

[변경 사항]
- posts → entries로 네이밍 변경
- 챌린지 하위 리소스로 구조 변경
- 수정(PATCH) 기능 제거 (참여작은 수정 불가)
```

### 5.6 투표 API (Like → Vote)

```
[기존 → 변경]
POST   /posts/{postId}/likes   → POST   /api/v1/entries/{entryId}/votes
DELETE /posts/{postId}/likes   → DELETE /api/v1/entries/{entryId}/votes

[신규]
GET    /api/v1/members/me/votes              내 투표 내역

[변경 사항]
- likes → votes로 네이밍 변경
- entries 하위 리소스로 구조 변경
```

### 5.7 응원 메시지 API (Comment → Support)

```
[기존 → 변경]
POST   /posts/{postId}/comments    → POST   /api/v1/entries/{entryId}/supports
GET    /posts/{postId}/comments    → GET    /api/v1/entries/{entryId}/supports
DELETE /comments/{commentId}       → DELETE /api/v1/supports/{supportId}

[변경 사항]
- comments → supports로 네이밍 변경
- entries 하위 리소스로 구조 변경
- 수정(PATCH) 기능 제거 (응원 메시지는 수정 불가)
```

---

## 6. 신규 추가 항목

### 6.1 신규 엔티티

| 엔티티 | 우선순위 | 설명 |
|:---|:---:|:---|
| `Pet` | P0 | 반려동물 정보 (필수) |
| `Challenge` | P0 | 챌린지 정보 (필수) |
| `Notification` | P2 | 알림 (추후) |
| `Reward` | P2 | 보상 내역 (추후) |
| `Follow` | P2 | 팔로우 (추후) |

### 6.2 신규 Enum

```java
// Species.java
public enum Species {
    DOG("강아지"),
    CAT("고양이"),
    BIRD("새"),
    FISH("물고기"),
    RABBIT("토끼"),
    HAMSTER("햄스터"),
    ETC("기타");
    
    private final String displayName;
}

// Gender.java
public enum Gender {
    MALE("수컷"),
    FEMALE("암컷"),
    UNKNOWN("모름");
    
    private final String displayName;
}

// ChallengeStatus.java
public enum ChallengeStatus {
    UPCOMING("예정"),
    ACTIVE("진행중"),
    ENDED("종료");
    
    private final String displayName;
}

// Role.java (기존에 없다면 추가)
public enum Role {
    USER, ADMIN
}
```

### 6.3 신규 DTO

```
[Pet 관련]
- PetCreateRequest
- PetUpdateRequest
- PetResponse
- PetListResponse

[Challenge 관련]
- ChallengeResponse
- ChallengeListResponse
- ChallengeRankingResponse
- RankingEntryResponse

[Entry 관련 - 기존 Post DTO 수정]
- EntryCreateRequest (기존 PostCreateRequest)
- EntryResponse (기존 PostResponse)
- EntryListResponse (기존 PostListResponse)
- EntryDetailResponse (기존 PostDetailResponse)

[Vote 관련 - 기존 Like DTO 수정]
- VoteResponse (신규)
- VoteListResponse (신규)

[Support 관련 - 기존 Comment DTO 수정]
- SupportCreateRequest (기존 CommentCreateRequest)
- SupportResponse (기존 CommentResponse)
- SupportListResponse (기존 CommentListResponse)
```

### 6.4 신규 Repository 메서드

```java
// ChallengeRepository
public interface ChallengeRepository extends JpaRepository<Challenge, Long> {
    
    // 상태별 챌린지 목록
    List<Challenge> findByStatusOrderByStartAtDesc(ChallengeStatus status);
    
    // 진행중인 챌린지
    @Query("SELECT c FROM Challenge c WHERE c.status = 'ACTIVE' AND c.endAt > :now")
    List<Challenge> findActiveChallenges(@Param("now") LocalDateTime now);
}

// EntryRepository (기존 PostRepository 수정)
public interface EntryRepository extends JpaRepository<Entry, Long> {
    
    // 챌린지별 참여작 목록 (Cursor 페이징)
    @Query("SELECT e FROM Entry e " +
           "JOIN FETCH e.pet " +
           "JOIN FETCH e.member " +
           "WHERE e.challenge.id = :challengeId " +
           "AND e.id < :cursorId " +
           "ORDER BY e.id DESC")
    List<Entry> findEntriesByChallengeWithCursor(
        @Param("challengeId") Long challengeId,
        @Param("cursorId") Long cursorId,
        Pageable pageable
    );
    
    // 챌린지별 랭킹 (투표수 기준)
    @Query("SELECT e FROM Entry e " +
           "JOIN FETCH e.pet " +
           "WHERE e.challenge.id = :challengeId " +
           "ORDER BY e.voteCount DESC")
    List<Entry> findRankingByChallenge(
        @Param("challengeId") Long challengeId,
        Pageable pageable
    );
    
    // 챌린지 + 펫 중복 참여 체크
    boolean existsByChallengeIdAndPetId(Long challengeId, Long petId);
    
    // 투표수 증가 (원자적 업데이트)
    @Modifying
    @Query("UPDATE Entry e SET e.voteCount = e.voteCount + 1 WHERE e.id = :entryId")
    void incrementVoteCount(@Param("entryId") Long entryId);
    
    // 투표수 감소 (원자적 업데이트)
    @Modifying
    @Query("UPDATE Entry e SET e.voteCount = e.voteCount - 1 WHERE e.id = :entryId")
    void decrementVoteCount(@Param("entryId") Long entryId);
}

// VoteRepository (기존 LikeRepository 수정)
public interface VoteRepository extends JpaRepository<Vote, Long> {
    
    // 중복 투표 체크
    boolean existsByEntryIdAndMemberId(Long entryId, Long memberId);
    
    // 투표 조회
    Optional<Vote> findByEntryIdAndMemberId(Long entryId, Long memberId);
    
    // 내 투표 목록
    List<Vote> findByMemberIdOrderByCreatedAtDesc(Long memberId);
}

// PetRepository (신규)
public interface PetRepository extends JpaRepository<Pet, Long> {
    
    // 내 펫 목록
    List<Pet> findByMemberIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long memberId);
    
    // 펫 존재 여부 (본인 소유)
    boolean existsByIdAndMemberId(Long petId, Long memberId);
}
```

---

## 7. 패키지 구조 변경

### 7.1 기존 구조 (추정)

```
com.example.kakaocommunity
├── config/
├── controller/
│   ├── AuthController
│   ├── UserController
│   ├── PostController
│   ├── CommentController
│   ├── LikeController
│   └── ImageController
├── service/
│   ├── AuthService
│   ├── UserService
│   ├── PostService
│   ├── CommentService
│   ├── LikeService
│   └── ImageService
├── repository/
│   ├── MemberRepository
│   ├── PostRepository
│   ├── CommentRepository
│   ├── LikeRepository
│   └── ImageRepository
├── entity/
│   ├── Member
│   ├── Post
│   ├── Comment
│   ├── Like
│   └── Image
├── dto/
│   ├── request/
│   └── response/
├── exception/
└── util/
```

### 7.2 변경 후 구조

```
com.petstar
├── global/
│   ├── config/
│   │   ├── SecurityConfig
│   │   ├── JpaConfig
│   │   ├── RedisConfig (추후)
│   │   └── S3Config
│   ├── exception/
│   │   ├── GlobalExceptionHandler
│   │   ├── ErrorCode
│   │   └── CustomException
│   ├── response/
│   │   ├── ApiResponse
│   │   └── ErrorResponse
│   ├── security/
│   │   ├── JwtTokenProvider
│   │   └── JwtAuthenticationFilter
│   └── util/
│
├── domain/
│   ├── auth/
│   │   ├── controller/AuthController
│   │   ├── service/AuthService
│   │   └── dto/
│   │
│   ├── member/
│   │   ├── controller/MemberController
│   │   ├── service/MemberService
│   │   ├── repository/MemberRepository
│   │   ├── entity/Member
│   │   └── dto/
│   │
│   ├── pet/                          ⭐ 신규
│   │   ├── controller/PetController
│   │   ├── service/PetService
│   │   ├── repository/PetRepository
│   │   ├── entity/Pet
│   │   └── dto/
│   │
│   ├── challenge/                    ⭐ 신규
│   │   ├── controller/ChallengeController
│   │   ├── service/ChallengeService
│   │   ├── repository/ChallengeRepository
│   │   ├── entity/Challenge
│   │   └── dto/
│   │
│   ├── entry/                        ⭐ Post → Entry
│   │   ├── controller/EntryController
│   │   ├── service/EntryService
│   │   ├── repository/EntryRepository
│   │   ├── entity/Entry
│   │   └── dto/
│   │
│   ├── vote/                         ⭐ Like → Vote
│   │   ├── controller/VoteController
│   │   ├── service/VoteService
│   │   ├── repository/VoteRepository
│   │   ├── entity/Vote
│   │   └── dto/
│   │
│   ├── support/                      ⭐ Comment → Support
│   │   ├── controller/SupportController
│   │   ├── service/SupportService
│   │   ├── repository/SupportMessageRepository
│   │   ├── entity/SupportMessage
│   │   └── dto/
│   │
│   └── image/
│       ├── controller/ImageController
│       ├── service/ImageService
│       ├── repository/ImageRepository
│       ├── entity/Image
│       └── dto/
│
└── infra/
    ├── s3/
    │   └── S3Uploader
    └── redis/                        ⭐ 추후 추가
        └── RedisService
```

---

## 8. DB 스키마 마이그레이션

### 8.1 테이블 변경

```sql
-- 1. 기존 테이블 이름 변경
ALTER TABLE post RENAME TO entry;
ALTER TABLE likes RENAME TO vote;  -- like는 예약어
ALTER TABLE comment RENAME TO support_message;

-- 2. 컬럼 이름 변경 (entry)
ALTER TABLE entry CHANGE COLUMN title caption VARCHAR(200);
ALTER TABLE entry CHANGE COLUMN content caption VARCHAR(200);  -- 둘 중 하나
ALTER TABLE entry CHANGE COLUMN post_image_id image_id BIGINT;
ALTER TABLE entry CHANGE COLUMN views vote_count INT DEFAULT 0;

-- 3. 컬럼 이름 변경 (vote)
ALTER TABLE vote CHANGE COLUMN post_id entry_id BIGINT;

-- 4. 컬럼 이름 변경 (support_message)
ALTER TABLE support_message CHANGE COLUMN post_id entry_id BIGINT;
```

### 8.2 신규 테이블 생성

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
    introduction VARCHAR(200),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME,
    
    FOREIGN KEY (member_id) REFERENCES member(id),
    FOREIGN KEY (profile_image_id) REFERENCES image(id),
    INDEX idx_pet_member_id (member_id)
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
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    FOREIGN KEY (thumbnail_id) REFERENCES image(id),
    INDEX idx_challenge_status (status),
    INDEX idx_challenge_end_at (end_at)
);

-- Entry 테이블 수정 (기존 post에 컬럼 추가)
ALTER TABLE entry ADD COLUMN challenge_id BIGINT NOT NULL AFTER id;
ALTER TABLE entry ADD COLUMN pet_id BIGINT NOT NULL AFTER challenge_id;
ALTER TABLE entry ADD CONSTRAINT fk_entry_challenge FOREIGN KEY (challenge_id) REFERENCES challenge(id);
ALTER TABLE entry ADD CONSTRAINT fk_entry_pet FOREIGN KEY (pet_id) REFERENCES pet(id);
ALTER TABLE entry ADD CONSTRAINT uk_entry_challenge_pet UNIQUE (challenge_id, pet_id);

-- 인덱스 추가
CREATE INDEX idx_entry_challenge_id ON entry(challenge_id);
CREATE INDEX idx_entry_pet_id ON entry(pet_id);
CREATE INDEX idx_entry_challenge_vote ON entry(challenge_id, vote_count DESC);

CREATE UNIQUE INDEX uk_vote_entry_member ON vote(entry_id, member_id);

CREATE INDEX idx_support_entry_id ON support_message(entry_id);
```

### 8.3 마이그레이션 순서

```
1. 백업 생성
   mysqldump -u root -p petstar > backup_before_migration.sql

2. 신규 테이블 생성 (pet, challenge)

3. 기존 테이블 이름 변경

4. 컬럼 이름 변경

5. FK 및 인덱스 추가

6. 테스트 데이터 확인

7. 애플리케이션 코드 배포
```

---

## 9. 단계별 작업 계획

### Phase 1: 프로젝트 기본 설정 (1일)

```
□ 프로젝트 이름 변경
  - 패키지명: com.example.kakaocommunity → com.petstar
  - application.yml 설정 변경
  - README.md 업데이트

□ 신규 Enum 생성
  - Species, Gender, ChallengeStatus, Role

□ 글로벌 설정 정리
  - 패키지 구조 변경 (global/, domain/, infra/)
```

### Phase 2: 엔티티 변경 (2일)

```
□ 기존 엔티티 수정
  - Member: 필드 추가 (role, pets, deletedAt)
  - Post → Entry: 이름 및 필드 변경
  - Like → Vote: 이름 변경
  - Comment → SupportMessage: 이름 변경

□ 신규 엔티티 생성
  - Pet
  - Challenge

□ 연관관계 설정
  - Member ↔ Pet (1:N)
  - Challenge ↔ Entry (1:N)
  - Pet ↔ Entry (1:N)
```

### Phase 3: Repository 변경 (1일)

```
□ 기존 Repository 수정
  - PostRepository → EntryRepository
  - LikeRepository → VoteRepository
  - CommentRepository → SupportMessageRepository

□ 신규 Repository 생성
  - PetRepository
  - ChallengeRepository

□ 쿼리 메서드 추가
  - 랭킹 조회, Cursor 페이징 등
```

### Phase 4: Service 변경 (2일)

```
□ 기존 Service 수정
  - PostService → EntryService
  - LikeService → VoteService
  - CommentService → SupportService

□ 신규 Service 생성
  - PetService
  - ChallengeService

□ 비즈니스 로직 추가
  - 챌린지 참여 검증
  - 투표 검증 (본인 제외, 중복 방지)
```

### Phase 5: Controller & DTO 변경 (2일)

```
□ 기존 Controller 수정
  - PostController → EntryController
  - LikeController → VoteController
  - CommentController → SupportController

□ 신규 Controller 생성
  - PetController
  - ChallengeController

□ DTO 전체 변경
  - Request/Response 클래스 이름 및 필드 변경
  - 신규 DTO 생성
```

### Phase 6: DB 마이그레이션 (1일)

```
□ 로컬 DB 마이그레이션 스크립트 작성
□ 테이블 변경 실행
□ 테스트 데이터 생성 스크립트 작성
□ 초기 챌린지 데이터 삽입
```

### Phase 7: 테스트 및 검증 (1일)

```
□ 단위 테스트 수정
□ 통합 테스트 실행
□ API 문서 업데이트 (Swagger)
□ Postman Collection 업데이트
```

---

## 10. 체크리스트

### 10.1 엔티티 체크리스트

| 작업 | 상태 |
|:---|:---:|
| Member 엔티티 수정 (role, pets 추가) | ☐ |
| Pet 엔티티 신규 생성 | ☐ |
| Challenge 엔티티 신규 생성 | ☐ |
| Post → Entry 변경 | ☐ |
| Like → Vote 변경 | ☐ |
| Comment → SupportMessage 변경 | ☐ |
| Image 엔티티 유지 확인 | ☐ |

### 10.2 Repository 체크리스트

| 작업 | 상태 |
|:---|:---:|
| PetRepository 생성 | ☐ |
| ChallengeRepository 생성 | ☐ |
| EntryRepository 메서드 추가 | ☐ |
| VoteRepository 메서드 추가 | ☐ |
| SupportMessageRepository 수정 | ☐ |

### 10.3 Service 체크리스트

| 작업 | 상태 |
|:---|:---:|
| PetService 생성 | ☐ |
| ChallengeService 생성 | ☐ |
| EntryService 수정 (참여 검증 추가) | ☐ |
| VoteService 수정 (투표 검증 추가) | ☐ |
| SupportService 수정 | ☐ |

### 10.4 Controller 체크리스트

| 작업 | 상태 |
|:---|:---:|
| API prefix 추가 (/api/v1) | ☐ |
| PetController 생성 | ☐ |
| ChallengeController 생성 | ☐ |
| EntryController 수정 | ☐ |
| VoteController 수정 | ☐ |
| SupportController 수정 | ☐ |

### 10.5 DTO 체크리스트

| 작업 | 상태 |
|:---|:---:|
| Pet 관련 DTO 생성 | ☐ |
| Challenge 관련 DTO 생성 | ☐ |
| Entry 관련 DTO 수정 | ☐ |
| Vote 관련 DTO 수정 | ☐ |
| Support 관련 DTO 수정 | ☐ |

### 10.6 DB 체크리스트

| 작업 | 상태 |
|:---|:---:|
| 백업 생성 | ☐ |
| pet 테이블 생성 | ☐ |
| challenge 테이블 생성 | ☐ |
| entry 테이블 수정 | ☐ |
| vote 테이블 수정 | ☐ |
| support_message 테이블 수정 | ☐ |
| 인덱스 추가 | ☐ |

### 10.7 기타 체크리스트

| 작업 | 상태 |
|:---|:---:|
| application.yml 수정 | ☐ |
| 패키지 구조 변경 | ☐ |
| Swagger 문서 업데이트 | ☐ |
| README.md 업데이트 | ☐ |
| 테스트 코드 수정 | ☐ |
| 테스트 데이터 스크립트 작성 | ☐ |

---

## 📝 문서 정보

| 항목 | 내용 |
|:---|:---|
| 버전 | 1.0.0 |
| 작성일 | 2025-01-30 |
| 작성자 | 김유찬 |
| 상태 | 초안 |
| 관련 문서 | PetStar_기획서.md |

---

> **다음 단계**: 최적화 로드맵 문서 작성
