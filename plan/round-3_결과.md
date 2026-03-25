# Round 3 결과: 읽기 API 캐싱 (Caffeine L1 Cache)

> **질문: "읽기 API에 캐시를 적용하면 커넥션 포화가 해소되어 성능이 개선되는가?"**
> **답: 그렇다. 전체 p95가 5,439ms → 48ms로 99.1% 개선되었다.**

---

## 1. 변경 내용

### 1-1. Caffeine 로컬 캐시 도입

```java
// CacheConfig.java
@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .maximumSize(200)
                .recordStats());
        return cacheManager;
    }
}
```

### 1-2. Controller 레벨 @Cacheable 적용

```java
// ChallengeController.java — 3개 읽기 API에 캐시 적용

@GetMapping
@Cacheable(value = "challengeList", key = "#status?.name() ?: 'ALL'")
public ApiResponse<List<ChallengeSummary>> getChallengeList(...)

@GetMapping("/{challengeId}/ranking")
@Cacheable(value = "ranking", key = "#challengeId + ':' + #limit")
public ApiResponse<List<RankingEntry>> getChallengeRanking(...)

@GetMapping("/{challengeId}/entries")
@Cacheable(value = "entryList", key = "#challengeId + ':' + #cursorId + ':' + #limit")
public ApiResponse<EntryResponseDto.ListDto> getEntryList(...)
```

**핵심 설계 결정: Controller 레벨 캐싱**
- Service가 아닌 Controller에 `@Cacheable` 적용
- 이유: 캐시 히트 시 Service 레이어까지 도달하지 않으므로 **DB 커넥션을 아예 획득하지 않음**
- Service 레벨 캐싱은 트랜잭션 시작 후 캐시를 확인하므로 커넥션을 이미 점유한 상태

### 테스트 조건

Round 1, 2와 **완전히 동일한 조건**:
- 동일 k6 스크립트 (`03_mixed-load-test.js`)
- 동일 인프라 (t3.small + db.t3.micro)
- 동일 데이터 (10K Members, 100K Entries, 30 Challenges)
- 동일 VU 단계 (10 → 100 → 300 → 500 → 800 → 300)

---

## 2. 측정 결과

### 2-1. Round 1 → 2 → 3 비교

| 지표 | Round 1 (Baseline) | Round 2A (투표 최적화) | **Round 3 (캐싱)** | **R1 대비 변화** |
|------|--------------------|-----------------------|---------------------|------------------|
| **전체 p95** | 5,439ms | 5,835ms | **48ms** | **-99.1%** |
| 챌린지 목록 p95 | 5,426ms | 5,889ms | **66ms** | **-98.8%** |
| 랭킹 p95 | 4,708ms | 4,869ms | **38ms** | **-99.2%** |
| 엔트리 p95 | 7,002ms | 7,595ms | **49ms** | **-99.3%** |
| 투표 p95 | 4,149ms | 4,257ms | **77ms** | **-98.1%** |
| 에러율 | 0.42% | 0.73% | **0.43%** | 동일 |
| HTTP RPS | 120.9 | 119.2 | **220.5** | **+82.4%** |
| 투표 성공 | 5,976 | 5,995 | **10,836** | **+81.3%** |
| 투표 중복 | 377 | 93 | **631** | - |
| 완료 이터레이션 | ~21,000 | ~21,000 | **37,983** | **+81%** |

### 2-2. 핵심 개선 수치

```
전체 p95:     5,439ms → 48ms   (99.1% 개선, 113배 빨라짐)
처리량(RPS):  120.9   → 220.5  (82.4% 증가)
투표 처리량:  5,976   → 10,836 (81.3% 증가)
한계 VU:      100 VUs → 800 VUs+ (8배 이상 확장)
```

---

## 3. 성공 원인 분석

### 3-1. 캐시가 DB 커넥션 포화를 해소한 메커니즘

```
[Before — Round 1/2: 캐시 없음]
VU 요청 → Controller → Service → @Transactional → HikariCP 커넥션 획득 → DB 쿼리
→ 100 VUs × 매 반복 3~4회 DB 호출 = 300~400 커넥션 요청/초
→ 30개 커넥션에 170개 대기 → p95 5,439ms

[After — Round 3: Caffeine L1 캐시]
VU 요청 → Controller → @Cacheable 히트 → 즉시 응답 (DB 접근 없음)
→ 캐시 미스 시에만 DB 호출 (10초에 1회)
→ 읽기 API의 DB 호출 90%+ 제거
→ 30개 커넥션에 여유 발생 → 투표도 커넥션 대기 없이 처리
→ p95 48ms
```

### 3-2. 왜 투표 p95도 98% 개선되었는가?

투표 API 자체는 변경하지 않았다. 하지만 **투표 p95도 4,149ms → 77ms로 98.1% 개선**되었다.

```
[원인: 커넥션 경합 해소]
Before: 읽기 API가 커넥션 30개 중 78%를 점유
       → 투표가 커넥션 획득을 위해 수 초간 대기
       → 투표 응답 시간 = 커넥션 대기(4초) + 실제 처리(10ms)

After:  읽기 API가 캐시 히트로 커넥션 미사용
       → 투표가 즉시 커넥션 획득
       → 투표 응답 시간 = 커넥션 대기(0ms) + 실제 처리(77ms)
```

→ **진짜 병목은 쿼리 속도가 아니라 커넥션 경합이었다.**

### 3-3. Round 2 실패가 Round 3 성공의 근거가 된 이유

```
Round 2 실패에서 얻은 핵심 데이터:
→ "읽기 API가 전체 DB 호출의 78%를 차지한다"

이 데이터가 Round 3의 가설을 정확하게 만들었다:
→ "읽기 78%를 캐시로 제거하면 커넥션 포화가 해소된다"
→ 실제 결과: p95 99.1% 개선으로 가설 검증 완료
```

---

## 4. 캐시 설계 근거

### 4-1. TTL 10초의 근거

```
[데이터 특성]
- 챌린지 목록: 운영자가 수동 생성 → 변경 빈도 극히 낮음 → 10초 TTL 충분
- 엔트리 목록: 참가 등록은 비투표 시간에 발생 → 부하 중 변경 거의 없음 → 10초 TTL 충분
- 랭킹: 투표로 실시간 변동 → BUT Redis Sorted Set에서 직접 서빙 → DB 캐시와 무관

[트레이드오프]
- 최대 10초간 stale 데이터 노출 가능
- BUT 투표 결과는 Redis에서 실시간 반영 (랭킹 API)
- 챌린지/엔트리 목록은 10초 지연이 UX에 영향 없음
```

### 4-2. maximumSize 200의 근거

```
- 챌린지 30개 × 상태필터 3종 = ~90개 캐시 키
- 엔트리 30개 챌린지 × 커서 조합 = ~100개 캐시 키
- 랭킹 30개 챌린지 × limit 조합 = ~30개 캐시 키
- 총 ~220개 → 200으로 설정, LRU eviction으로 관리
```

---

## 5. 전체 스토리 (Round 1 → 2 → 3)

```
[1막] Round 1 — 병목 발견
  혼합 부하 테스트로 HikariCP 커넥션 풀 포화 발견 (100 VUs에서 30/30)
  → 가설: "투표의 DB 호출을 줄이면 커넥션 여유 생긴다"

[2막] Round 2 — 가설 검증 실패
  투표 DB exists → Redis SADD 전환 (DB 2회 → 1회)
  → 결과: p95 5,439ms → 5,835ms (개선 없음, 오히려 악화)
  → 교훈: 데이터를 보니 읽기가 DB 호출의 78%를 차지
  → 투표 1회 줄여봐야 전체에 미미한 영향

[3막] Round 3 — 올바른 가설로 재도전
  읽기 API에 Caffeine L1 캐시 적용 (Controller 레벨)
  → 결과: p95 5,439ms → 48ms (99.1% 개선)
  → RPS 120.9 → 220.5 (82.4% 증가)
  → 800 VUs까지 안정 처리 (이전 한계: 100 VUs)

[핵심 메시지]
"가설이 틀릴 수 있다. 투표가 병목이라고 생각했지만,
데이터는 읽기가 커넥션의 78%를 차지한다고 말했다.
측정 결과를 근거로 가설을 수정하고 재도전하는 것이 진짜 엔지니어링."
```

---

## 6. 부하 테스트 이력

| 날짜 | 테스트 | 전체 p95 | RPS | 에러율 | 비고 |
|------|--------|----------|-----|--------|------|
| 2026-03-13 18:50 | Round 1 Baseline | 5,439ms | 120.9 | 0.42% | DB 커넥션 풀 포화 |
| 2026-03-13 19:33 | Round 2A (DB exists→Redis SADD) | 5,835ms | 119.2 | 0.73% | 개선 없음 (읽기가 78%) |
| 2026-03-13 20:32 | **Round 3 (Caffeine L1 캐시)** | **48ms** | **220.5** | **0.43%** | **99.1% 개선** |
