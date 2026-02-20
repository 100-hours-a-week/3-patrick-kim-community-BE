/**
 * PetStar 단계별 부하테스트 스크립트 (Phase 9)
 *
 * 시나리오: 점진적 부하 증가로 시스템 한계점 파악
 *   - Warmup: JIT 컴파일, Connection Pool 초기화
 *   - Normal: 평상시 트래픽 시뮬레이션
 *   - Peak: 피크 시간대 (챌린지 종료 직전)
 *   - Stress: 시스템 한계 테스트
 *
 * 가정:
 *   - 동시 접속 100명 ≈ DAU 1,000~2,000명
 *   - 동시 접속 200명 ≈ DAU 2,000~4,000명
 *   - 동시 접속 300명 ≈ DAU 3,000~6,000명
 *   - 초기 서비스 ~ 성장 단계 커버
 *
 * 총 테스트 시간: 5분 30초
 *   - Warmup:     0:00 ~ 0:30 (30초)
 *   - Normal:     0:30 ~ 2:30 (2분)
 *   - Peak:       2:30 ~ 4:30 (2분)
 *   - Stress:     4:30 ~ 5:30 (1분)
 *
 * 사용:
 *   k6 run 02_staged-load-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ============================================
// Custom Metrics
// ============================================
const errorRate = new Rate('errors');
const challengesTrend = new Trend('challenges_duration');
const entriesTrend = new Trend('entries_duration');
const rankingTrend = new Trend('ranking_duration');

// ============================================
// Configuration
// ============================================
const BASE_URL = 'http://43.200.83.214:8080';

// ============================================
// Test Options (단계별 시나리오)
// ============================================
export const options = {
    scenarios: {
        // Stage 1: Warmup (JIT 컴파일, Connection Pool 워밍업)
        //   - 낮은 부하로 시스템 초기화
        //   - 이 단계 결과는 측정에서 제외해도 됨
        warmup: {
            executor: 'constant-vus',
            vus: 10,
            duration: '30s',
            startTime: '0s',
        },

        // Stage 2: Normal Load (평상시 트래픽)
        //   - 100 VUs ≈ 초당 60~100 요청
        //   - 일반적인 서비스 운영 상태
        normal_load: {
            executor: 'constant-vus',
            vus: 100,
            duration: '2m',
            startTime: '30s',
        },

        // Stage 3: Peak Load (피크 시간대)
        //   - 200 VUs ≈ 초당 120~200 요청
        //   - 챌린지 종료 직전 트래픽 집중 시뮬레이션
        peak_load: {
            executor: 'constant-vus',
            vus: 200,
            duration: '2m',
            startTime: '2m30s',
        },

        // Stage 4: Stress Test (한계 테스트)
        //   - 300 VUs ≈ 초당 180~300 요청
        //   - 시스템이 어디까지 버티는지 확인
        //   - 에러 발생 시점 파악
        stress: {
            executor: 'constant-vus',
            vus: 300,
            duration: '1m',
            startTime: '4m30s',
        },
    },

    // 성능 목표 (threshold)
    thresholds: {
        http_req_duration: ['p(95)<500'],   // p95 < 500ms
        errors: ['rate<0.05'],               // 에러율 < 5%
    },
};

// ============================================
// Helper Functions
// ============================================
function getRandomChallengeId() {
    // 50개 챌린지 중 랜덤 선택 (시딩 데이터 기준)
    return Math.floor(Math.random() * 50) + 1;
}

// ============================================
// Test Scenario
// ============================================
export default function () {
    const challengeId = getRandomChallengeId();

    // 1. 챌린지 목록 조회
    let res = http.get(`${BASE_URL}/api/v1/challenges`);
    challengesTrend.add(res.timings.duration);
    check(res, { 'challenges status 200': (r) => r.status === 200 });
    errorRate.add(res.status !== 200);

    // 빠른 페이지 전환 시뮬레이션 (0.5초)
    sleep(0.5);

    // 2. 참여작 목록 조회
    res = http.get(`${BASE_URL}/api/v1/challenges/${challengeId}/entries?limit=10`);
    entriesTrend.add(res.timings.duration);
    check(res, { 'entries status 200': (r) => r.status === 200 });
    errorRate.add(res.status !== 200);

    sleep(0.5);

    // 3. 랭킹 조회 (메인 타겟)
    //    - 가장 복잡한 쿼리 (ORDER BY + JOIN)
    //    - 최적화 효과가 가장 크게 나타나는 API
    res = http.get(`${BASE_URL}/api/v1/challenges/${challengeId}/ranking?limit=10`);
    rankingTrend.add(res.timings.duration);
    check(res, { 'ranking status 200': (r) => r.status === 200 });
    errorRate.add(res.status !== 200);

    sleep(0.5);
}
