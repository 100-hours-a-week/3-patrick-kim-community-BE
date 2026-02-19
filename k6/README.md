# PetStar 부하테스트

## 구성 파일

| 파일 | 설명 |
|:-----|:-----|
| `load-test.js` | k6 테스트 시나리오 |
| `seed.sh` | 데이터 시딩 스크립트 |
| `seed-fast.sql` | Challenge 시딩 SQL |
| `run-test.sh` | 테스트 실행 스크립트 |

## 데이터 시딩

```bash
# RDS에 테스트 데이터 시딩
chmod +x seed.sh
./seed.sh petstar-db.xxx.rds.amazonaws.com admin [password] petstar
```

### 시딩 데이터 규모
- Member: 10,000건
- Pet: 15,000건
- Challenge: 50건
- Entry: 100,000건
- Vote: 1,000,000건

## 테스트 실행

```bash
# 실행 권한 부여
chmod +x run-test.sh

# Smoke Test (기본 동작 확인)
./run-test.sh smoke

# Quick Test (빠른 테스트)
./run-test.sh quick

# Load Test (점진적 부하)
./run-test.sh load

# Before 측정 (50/100/200 VUs)
./run-test.sh before
```

## 테스트 시나리오

### Smoke Test
- VUs: 5
- Duration: 30초
- 목적: 기본 동작 확인

### Load Test
- VUs: 0 → 50 → 100 → 200 → 0
- Duration: 11분
- 목적: 점진적 부하 테스트

### Before 측정
- VUs: 50, 100, 200 각각 2분
- 목적: 최적화 전 베이스라인 측정

## API 테스트 대상

| API | 설명 |
|:----|:-----|
| GET /api/v1/challenges | 챌린지 목록 |
| GET /api/v1/challenges/{id}/entries | 참여작 목록 |
| GET /api/v1/challenges/{id}/ranking | 랭킹 조회 |

## 결과 확인

```bash
# 결과 파일
ls -la results/

# JSON 결과 파싱
cat results/summary.json | jq .
```

## 주요 메트릭

| 메트릭 | 설명 | 목표 |
|:-------|:-----|:-----|
| http_req_duration (p95) | 95% 응답시간 | < 2000ms |
| errors | 에러율 | < 10% |
| challenge_list_duration | 챌린지 목록 응답시간 | < 1000ms |
| entries_list_duration | 참여작 목록 응답시간 | < 1500ms |
| ranking_duration | 랭킹 조회 응답시간 | < 1000ms |