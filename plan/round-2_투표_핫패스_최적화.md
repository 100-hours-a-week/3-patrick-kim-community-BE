# Round 2: 투표 Hot Path 최적화

> **질문: "투표 API에서 DB를 제거하면 얼마나 빨라지는가? Phase 10-2의 실패를 극복할 수 있는가?"**

---

## 1. 왜 이 질문인가

### 이미 알고 있는 사실

```
현재 투표 Hot Path (VoteService.voteAsync()):
  ① DB findById          — Entry 존재 확인 + challengeId 획득
  ② DB exists            — 중복 투표 체크
  ③ Redis Pipeline       — ZINCRBY + ZSCORE (1 RTT)
  ④ SQS Fire & Forget    — 비동기 전송

→ DB 2회 호출이 전체 응답 시간의 대부분을 차지
→ 50 VUs Hot Spot에서 p95 658ms
```

### Phase 10-2에서 이미 실패한 경험

```
Phase 10-2: DB exists → Redis SISMEMBER로 변경
  결과: p95 516ms → 1,300ms (회귀!)
  원인: Lettuce 단일 커넥션에 4개 Redis 명령이 직렬화

Phase 10-3: Lettuce 커넥션 풀링 추가 (max-active: 20)
  결과: p95 688ms → 658ms (미미한 개선)
  이유: DB 중복 체크를 유지했기 때문에 Redis 부하가 적어 풀링 효과 미미
```

**핵심**: Phase 10-3에서 Lettuce 풀링을 추가했지만 **그 위에서 다시 Redis 전환을 시도하지 않았다.** 풀링이 있는 상태에서 재시도하면 Phase 10-2의 실패를 극복할 수 있는가?

### 이미 만들어놓은 코드가 있다

```java
// RankingRedisService.java — Line 151
// Phase 10-3에서 만들었지만 실제로 사용하지 않는 메서드
public List<Object> recordAndIncrementPipelined(
    Long entryId, Integer memberId, Long challengeId) {
    // Pipeline: SADD + ZINCRBY + ZSCORE = 1 RTT
}
```

---

## 2. 이 Round가 시작되는 조건

**Round 1의 결과에 따라** 이 Round의 방향이 결정된다.

```
Round 1에서 발견될 수 있는 것:

경우 1: HikariCP 커넥션 포화가 병목
  → 이 Round에서 "DB 호출 줄이기"가 직접적 해결책이 됨
  → 투표의 DB 호출을 줄이면 읽기에도 커넥션이 돌아감

경우 2: 투표 자체는 괜찮지만 읽기가 투표에 밀림
  → 투표의 DB 의존을 줄이면 읽기/쓰기 격리 효과
  → 또는 읽기에 캐시를 적용하는 것이 더 효과적일 수 있음

경우 3: Redis가 이미 병목
  → DB 제거보다 Redis 최적화가 먼저
  → 이 Round의 방향이 바뀜

경우 4: App Server CPU가 한계
  → DB든 Redis든 의미 없음, 인프라 스케일업 필요
```

---

## 3. 실험 설계

### 실험 A: DB exists 제거 → Redis SADD로 대체

**가설**: "Phase 10-3에서 추가한 Lettuce 커넥션 풀링(max-active: 20) 위에서 Redis SADD 중복 체크를 적용하면, Phase 10-2의 Lettuce 단일 커넥션 병목이 해소되어 p95가 658ms에서 크게 개선될 것이다."

```
[변경]
Before (현재):
  ① DB findById       — HikariCP
  ② DB exists         — HikariCP
  ③ Redis Pipeline    — ZINCRBY + ZSCORE

After (실험 A):
  ① DB findById       — HikariCP (유지, Entry 검증 필요)
  ② Redis Pipeline    — SADD + ZINCRBY + ZSCORE (1 RTT)
  → DB 1회 제거, Redis 명령 1개 추가 (but Pipeline이라 RTT 동일)
```

```java
// VoteService.voteAsync() 변경안

// 1. Entry 조회 (DB — 유지)
Entry entry = entryRepository.findById(entryId)
    .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));
validateVote(entry, memberId);

// 2. Redis Pipeline: 중복체크(SADD) + 랭킹(ZINCRBY) + 점수(ZSCORE) = 1 RTT
List<Object> results = rankingRedisService
    .recordAndIncrementPipelined(entryId, memberId, challengeId);

Long added = (Long) results.get(0);  // SADD: 1=신규, 0=중복
if (added == 0L) {
    // 이미 투표한 경우 — Redis에서 ZINCRBY한 것을 롤백
    rankingRedisService.decrementVote(challengeId, entryId);
    throw new GeneralException(ErrorStatus._BAD_REQUEST);
}

// 3. SQS 전송 (기존 동일)
```

**측정**: k6 50 VUs Hot Spot (Phase 10과 동일 조건)

**성공 기준**: p95 < 300ms (658ms 대비 50%+ 개선)

**실패 시**: Phase 10-2 때와 같이 Redis가 병목이면 원인 분석 (풀 사이즈? 명령 수? 네트워크?)

### 실험 B: DB findById도 제거 → Redis Hash 캐시

**전제**: 실험 A가 성공한 후 진행. DB findById가 새로운 병목이 되는 경우.

**가설**: "Entry 메타데이터(challengeId, memberId)를 Redis Hash에 캐시하면, 투표 Hot Path에서 DB 호출을 완전히 제거하여 p95 < 100ms를 달성할 수 있다."

```
[변경]
Before (실험 A 후):
  ① DB findById       — HikariCP (남은 유일한 DB 호출)
  ② Redis Pipeline    — SADD + ZINCRBY + ZSCORE

After (실험 B):
  ① Redis HGET        — Entry 캐시에서 challengeId, memberId 조회
  ② Redis Pipeline    — SADD + ZINCRBY + ZSCORE
  → DB 0회, Redis만으로 투표 처리

Entry 캐시:
  키: entry:{entryId}
  값: Hash {challengeId, memberId, status}
  TTL: 챌린지 종료까지 (or 1시간 + lazy refresh)
  적재: Entry 생성 시 or 첫 투표 시 (Cache-aside)
```

**측정**: k6 50 VUs Hot Spot

**성공 기준**: p95 < 100ms

**위험**: 캐시된 Entry 정보가 stale일 수 있음 (삭제된 Entry에 투표). SQS Consumer에서 최종 검증하므로 비즈니스적으로 안전.

### 실험 C: Lettuce 풀 사이즈 튜닝

실험 A 또는 B와 병행 가능.

**가설**: "Lettuce max-active를 10 / 20 / 30 / 50으로 변경하면서 최적값을 찾을 수 있다."

```
[측정 매트릭스]
max-active=10: p95=___ms, Redis avg latency=___ms
max-active=20: p95=___ms (현재 설정)
max-active=30: p95=___ms
max-active=50: p95=___ms

→ 그래프로 시각화: 풀 사이즈 vs p95 곡선
→ 수확 체감점(diminishing returns) 발견
```

### 실험 D: (실패 시) 왜 실패했는가 분석

Phase 10-2에서 했던 것처럼, 실패 원인을 깊이 파고든다.

```
분석 방법:
1. Redis INFO 명령으로 실시간 메트릭 수집
   - connected_clients, instantaneous_ops_per_sec
   - total_connections_received (풀링이 실제로 동작하는지)

2. p6spy로 DB 쿼리 실행 시간 vs Redis 명령 시간 비교
   - DB exists: 평균 ___ms
   - Redis SADD: 평균 ___ms
   - Redis Pipeline 3cmd: 평균 ___ms

3. 스레드 덤프 (jstack)
   - Lettuce 이벤트 루프에서 블로킹되는 스레드가 있는지
   - HikariCP 대기 스레드 수

4. 네트워크 레이턴시
   - App → Redis 네트워크 RTT (ping)
   - App → DB 네트워크 RTT
   → Redis가 모니터링 서버에 있으므로 네트워크 비용이 더 클 수 있음!
```

---

## 4. 측정 포인트

모든 실험에서 동일하게 측정한다.

```
[투표 API]
- p50, p95, p99 응답 시간
- 초당 처리량 (RPS)
- 에러율
- DB 호출 횟수 (p6spy 로그)
- Redis 명령 횟수 (Redis MONITOR or INFO)

[인프라]
- HikariCP active/pending connections
- Lettuce pool active/idle connections
- Redis connected_clients, ops/sec
- App CPU, Memory

[정합성]
- Redis 투표 수 vs DB 투표 수 (5분 후)
- 중복 투표 차단 정확도 (DB UK 위반 건수)
```

---

## 5. 포트폴리오 서술 구조

이 Round의 결과는 다음과 같이 서술된다:

```
[3막 구조]

1막 (Phase 10-2, 과거): Redis 전환 시도 → 실패
  - Redis SISMEMBER로 DB exists 대체
  - 결과: p95 1,300ms (회귀)
  - 원인: Lettuce 단일 커넥션에 50개 요청 직렬화

2막 (Phase 10-3, 과거): 인프라 보강
  - Lettuce 커넥션 풀링 (max-active: 20)
  - 하지만 DB 중복 체크를 유지 → 효과 미미 (688→658ms)

3막 (Round 2, 이번): 재도전
  - 풀링 인프라 위에서 Redis SADD 전환 재시도
  - Pipeline으로 3 명령을 1 RTT로 압축
  - 결과: p95 ___ms (___% 개선)

핵심 메시지:
"같은 기술(Redis)이라도 인프라(커넥션 모델)에 따라
결과가 180도 달라진다. 실패 원인을 정확히 파악하고
인프라를 보강한 뒤 재시도하여 성공했다."
```

---

## 6. 실험 순서 정리

```
① Round 1 완료 → 병목 원인 확인
②   └─ DB 커넥션이 병목이면 → 실험 A 진행
②   └─ Redis가 병목이면 → 실험 C (풀 튜닝) 먼저
②   └─ CPU가 병목이면 → 인프라 분석 (이 Round 범위 밖)

③ 실험 A 실행: DB exists → Redis SADD
④   └─ 성공 (p95 개선) → 실험 B 진행 (DB findById도 제거)
④   └─ 실패 (p95 악화) → 실험 D (원인 분석) → 새 가설

⑤ 실험 B 또는 C 실행

⑥ 최종 Before/After 정리
```

**핵심: 실험 결과가 다음 실험을 결정한다. 미리 정해놓지 않는다.**
