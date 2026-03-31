# SQS Standard Queue vs FIFO Queue 선택 분석

> **결론**: Standard Queue 유지
> **근거**: PetStar 투표의 특성상 FIFO가 제공하는 기능이 불필요하거나 이미 애플리케이션 레벨에서 해결됨

---

## 1. PetStar 투표 시스템 요구사항

```
투표 메시지 특성:
  - 크기: ~100 bytes (memberId, entryId, challengeId, timestamp)
  - 순서: 불필요 (투표는 교환법칙 성립 — A→B든 B→A든 결과 동일)
  - 중복 방지: 필수 (같은 사용자가 같은 Entry에 2표 불가)
  - 유실: 불가 (투표는 반드시 DB에 반영)

트래픽 패턴:
  - 평상시: 분당 100~500 투표
  - 마감 직전: 분당 10,000+ (스파이크)
  - Hot Spot: 인기 Entry에 투표 집중
```

---

## 2. Standard vs FIFO 비교

| 기준 | Standard Queue | FIFO Queue |
|:-----|:--------------|:-----------|
| **전송 보장** | At-least-once (중복 가능) | Exactly-once |
| **순서 보장** | 보장 안 함 | MessageGroupId 내 보장 |
| **처리량** | 무제한 | 300 TPS/MessageGroupId |
| **비용** | 100만 건/월 무료 | 100만 건/월 무료 |
| **필수 헤더** | 없음 | MessageGroupId + MessageDeduplicationId |
| **중복 제거 윈도우** | 없음 | 5분 |

---

## 3. FIFO가 제공하는 것 — PetStar에 필요한가?

### 3.1 Exactly-once 처리

**FIFO의 제공**: 같은 메시지가 2번 전달되지 않음 (MessageDeduplicationId 기반)

**PetStar에서 이미 해결된 이유**:
```
방어선 1: Redis SADD 중복 체크 (O(1), API 레벨)
방어선 2: Consumer의 existsByEntryIdAndMemberId() (DB 레벨)
방어선 3: DB Unique 제약조건 uk_vote_entry_member (entry_id, member_id)

→ 3중 방어로 중복 투표 완전 차단
→ FIFO의 Exactly-once는 "이미 있는 안전장치 위에 또 안전장치"
```

**결론**: 불필요한 중복 안전장치. 애플리케이션 레벨 idempotency가 더 확실하고 범용적.

### 3.2 순서 보장

**FIFO의 제공**: 같은 MessageGroupId 내에서 FIFO 순서 보장

**PetStar에서 불필요한 이유**:
```
투표 시나리오:
  User A가 Entry 100에 투표 (t=1)
  User B가 Entry 100에 투표 (t=2)

  → A가 먼저든 B가 먼저든 결과: voteCount = 기존 + 2
  → 투표는 교환법칙(commutative)이 성립하는 연산
  → 순서가 바뀌어도 최종 상태가 동일
```

순서 보장이 필요한 경우: 결제 처리 (결제→환불 순서 중요), 채팅 메시지, 상태 전이 등
투표는 이에 해당하지 않음.

### 3.3 MessageGroupId 기반 병렬화

**FIFO의 제공**: 같은 MessageGroupId(entryId)는 순차 처리, 다른 그룹은 병렬 처리

**PetStar 현황**:
```
현재: maxConcurrentMessages=1 (순차 처리)
이유: 같은 Entry에 동시 UPDATE 시 데드락 방지

FIFO + MessageGroupId=entryId 사용 시:
  → 같은 Entry는 자동 순차 → 데드락 없음
  → 다른 Entry는 병렬 → 처리량 증가

BUT:
  → 현재 Consumer 처리량 (20-50 msg/sec) > 예상 투표량 (33 msg/sec at 마감 직전)
  → 병렬화 필요성이 아직 없음
  → 병목이 되면 그때 검토해도 늦지 않음 (YAGNI 원칙)
```

**결론**: nice-to-have이지만, 현재 순차 처리 + retry로 충분.

---

## 4. FIFO 선택 시 생기는 제약

### 4.1 처리량 제한

```
FIFO 기본: 300 TPS per MessageGroupId
FIFO 고처리량 모드: 3,000 TPS per MessageGroupId

MessageGroupId = entryId 사용 시:
  → 인기 Entry에 초당 300표 이상 몰리면 스로틀링
  → PetStar 현재 규모에서는 문제없지만, 확장 시 제약

Standard: 무제한 TPS, 확장 제약 없음
```

### 4.2 코드 복잡도 증가

```java
// Standard: 단순 전송
sqsTemplate.send(queueName, payload);

// FIFO: 필수 헤더 추가
sqsTemplate.send(to -> to
    .queue(queueName)
    .payload(payload)
    .header("message-group-id", String.valueOf(entryId))
    .header("message-deduplication-id", voteId)
);
```

### 4.3 중복 제거 윈도우 제한

```
FIFO 중복 제거: 5분 윈도우
→ 같은 MessageDeduplicationId가 5분 이내에 다시 오면 무시
→ 5분 이후에 같은 ID가 오면 중복 처리될 수 있음
→ 결국 DB UK 제약조건이 최종 방어선 역할을 해야 함

Standard + DB UK: 시간 제한 없는 완전한 중복 방지
```

---

## 5. 최종 결론

### Standard Queue를 선택하는 이유

```
1. "문제에 맞는 도구를 선택하라"
   - 투표의 핵심 요구: 중복 방지 + 유실 방지
   - 중복 방지: DB UK 제약조건으로 완전 해결 (시간 제한 없음)
   - 유실 방지: DLQ + 정합성 스케줄러로 해결
   - FIFO의 Exactly-once, 순서 보장 → 투표에 불필요

2. "불필요한 복잡성을 추가하지 마라"
   - FIFO 전환 시: 헤더 추가, 큐 재생성, TPS 제약 관리
   - 얻는 것: 이미 해결된 문제의 추가 안전장치
   - 복잡성 대비 이득이 낮음

3. "확장 가능성"
   - Standard: 무제한 TPS, 제약 없음
   - FIFO: 300 TPS/그룹 제한 → 스케일 시 고려 필요

4. "비용"
   - 둘 다 100만 건/월 무료
   - 비용 차이 없음 → 비용은 선택 기준이 아님
```

### FIFO가 적합한 서비스 예시

```
결제 시스템: 결제 → 환불 순서 보장 필수
주문 상태 전이: 접수 → 확인 → 배송 → 완료 (순서 중요)
채팅 메시지: 메시지 순서 보장 필수
재고 차감: 동일 상품 재고 순차 처리 필요

→ 이런 서비스에는 FIFO가 적합
→ PetStar 투표는 이에 해당하지 않음
```

---

## 6. 핵심 교훈

```
"기술 선택에서 중요한 것은 무엇을 선택했느냐가 아니라, 왜 선택했느냐이다"

FIFO가 나쁜 선택이 아님. 하지만:
- 이미 해결된 문제를 큐 레벨에서 다시 해결할 필요는 없음
- 불필요한 기능(순서 보장)을 위해 제약(TPS 제한)을 감수할 이유 없음
- 간단한 도구로 충분하면 간단한 도구를 쓰는 것이 좋은 엔지니어링
```
