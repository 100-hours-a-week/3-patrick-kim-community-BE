/**
 * PetStar 혼합 부하 테스트 (Round 1)
 *
 * 목적: 읽기 + 쓰기가 동시에 발생하는 실제 트래픽에서 시스템 한계점 탐색
 *
 * 기존 테스트와의 차이:
 *   - 01, 02: 읽기 API만 테스트 (챌린지/엔트리/랭킹 조회)
 *   - 03 (이 스크립트): 읽기 70% + 투표 30% 혼합
 *
 * 사용자 행동 모델:
 *   구경 유저 (70%): 챌린지 목록 → 랭킹 확인 → 엔트리 탐색
 *   투표 유저 (30%): 랭킹 확인 → 투표 → 랭킹 재확인
 *
 * 투표 분포 (Hot Spot):
 *   상위 5개 Entry에 투표의 50% 집중 (인기 사진 시뮬레이션)
 *   나머지 Entry에 50% 분산
 *
 * 단계별 부하:
 *   Warmup:    10 VUs,  30초  (JIT, Pool 워밍업)
 *   Normal:   100 VUs,   2분  (MAU ~2만 평시)
 *   Peak:     300 VUs,   2분  (MAU ~6만 피크)
 *   Stress:   500 VUs,   2분  (MAU ~10만 피크)
 *   Spike:    800 VUs,   1분  (마감 직전 스파이크)
 *   Recovery: 300 VUs,   1분  (스파이크 후 회복)
 *
 * 총 테스트 시간: 8분 30초
 *
 * 사용:
 *   k6 run 03_mixed-load-test.js
 *   k6 run --env BASE_URL=http://localhost:8080 03_mixed-load-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ============================================
// Custom Metrics — 읽기/쓰기 분리 측정
// ============================================

// 읽기 API
const challengeListTrend = new Trend('read_challenge_list', true);
const rankingTrend = new Trend('read_ranking', true);
const entriesTrend = new Trend('read_entries', true);

// 쓰기 API
const voteTrend = new Trend('write_vote', true);

// 전체
const errorRate = new Rate('errors');
const voteErrors = new Counter('vote_errors');
const voteSuccess = new Counter('vote_success');
const voteDuplicate = new Counter('vote_duplicate');

// ============================================
// Configuration
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://3.34.221.62:8080';

// ACTIVE 챌린지 ID (시딩 데이터: 1~30)
const ACTIVE_CHALLENGE_IDS = Array.from({ length: 30 }, (_, i) => i + 1);

// Hot Spot Entry IDs — 인기 사진 (투표의 50%가 여기에 집중)
// 챌린지 1~5의 첫 번째 Entry를 인기 사진으로 가정
const HOT_ENTRY_IDS = [1, 2001, 4001, 6001, 8001];

// 일반 Entry ID 범위 (전체 100,000건, 챌린지당 ~2,000건)
const TOTAL_ENTRIES = 100000;

// Member ID 범위 (시딩 데이터: 10,000명)
const TOTAL_MEMBERS = 10000;

// ============================================
// Test Options
// ============================================
export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus',
            vus: 10,
            duration: '30s',
            startTime: '0s',
            tags: { phase: 'warmup' },
        },
        normal: {
            executor: 'constant-vus',
            vus: 100,
            duration: '2m',
            startTime: '30s',
            tags: { phase: 'normal' },
        },
        peak: {
            executor: 'constant-vus',
            vus: 300,
            duration: '2m',
            startTime: '2m30s',
            tags: { phase: 'peak' },
        },
        stress: {
            executor: 'constant-vus',
            vus: 500,
            duration: '2m',
            startTime: '4m30s',
            tags: { phase: 'stress' },
        },
        spike: {
            executor: 'constant-vus',
            vus: 800,
            duration: '1m',
            startTime: '6m30s',
            tags: { phase: 'spike' },
        },
        recovery: {
            executor: 'constant-vus',
            vus: 300,
            duration: '1m',
            startTime: '7m30s',
            tags: { phase: 'recovery' },
        },
    },

    thresholds: {
        // 전체
        http_req_duration: ['p(95)<1000'],
        errors: ['rate<0.05'],

        // 읽기 API
        read_challenge_list: ['p(95)<500'],
        read_ranking: ['p(95)<500'],
        read_entries: ['p(95)<500'],

        // 쓰기 API
        write_vote: ['p(95)<1000'],
    },
};

// ============================================
// Helper Functions
// ============================================

function getRandomElement(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function getRandomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

/**
 * Hot Spot 분포 투표 대상 Entry 선택
 *
 * 50% 확률로 인기 Entry (5개 중 1개)
 * 50% 확률로 일반 Entry (100,000개 중 랜덤)
 */
function getVoteEntryId() {
    if (Math.random() < 0.5) {
        // Hot Spot: 인기 Entry
        return getRandomElement(HOT_ENTRY_IDS);
    } else {
        // 일반: 전체 Entry 중 랜덤
        return getRandomInt(1, TOTAL_ENTRIES);
    }
}

/**
 * VU별 고유 Member ID 생성
 *
 * 중복 투표 방지를 위해 VU ID + iteration 기반으로 memberId 생성
 * memberId 범위: 1 ~ 10,000
 */
function getMemberId() {
    // VU 번호 * 반복 횟수로 다양한 memberId 생성
    // 중복 투표(400 에러)가 발생하면 그냥 넘어감 (실제 서비스에서도 발생)
    return getRandomInt(1, TOTAL_MEMBERS);
}

// ============================================
// User Behavior Scenarios
// ============================================

/**
 * 구경 유저 시나리오 (70%)
 * 챌린지 목록 → 랭킹 확인 → 엔트리 탐색
 */
function browseScenario() {
    const challengeId = getRandomElement(ACTIVE_CHALLENGE_IDS);

    // 1. 챌린지 목록 조회 (홈 화면)
    let res = http.get(`${BASE_URL}/api/v1/challenges`, {
        tags: { name: 'challenge_list', type: 'read' },
    });
    challengeListTrend.add(res.timings.duration);
    let ok = check(res, { 'challenge_list 200': (r) => r.status === 200 });
    errorRate.add(!ok);

    sleep(getRandomInt(1, 2));

    // 2. 랭킹 조회
    res = http.get(`${BASE_URL}/api/v1/challenges/${challengeId}/ranking?limit=10`, {
        tags: { name: 'ranking', type: 'read' },
    });
    rankingTrend.add(res.timings.duration);
    ok = check(res, { 'ranking 200': (r) => r.status === 200 });
    errorRate.add(!ok);

    sleep(getRandomInt(1, 2));

    // 3. 엔트리 목록 탐색
    res = http.get(`${BASE_URL}/api/v1/challenges/${challengeId}/entries?limit=10`, {
        tags: { name: 'entries', type: 'read' },
    });
    entriesTrend.add(res.timings.duration);
    ok = check(res, { 'entries 200': (r) => r.status === 200 });
    errorRate.add(!ok);

    sleep(getRandomInt(1, 2));
}

/**
 * 투표 유저 시나리오 (30%)
 * 랭킹 확인 → 투표 → 랭킹 재확인
 */
function voteScenario() {
    const challengeId = getRandomElement(ACTIVE_CHALLENGE_IDS);
    const entryId = getVoteEntryId();
    const memberId = getMemberId();

    // 1. 랭킹 확인 (투표 전에 순위 확인)
    let res = http.get(`${BASE_URL}/api/v1/challenges/${challengeId}/ranking?limit=10`, {
        tags: { name: 'ranking', type: 'read' },
    });
    rankingTrend.add(res.timings.duration);
    let ok = check(res, { 'ranking 200': (r) => r.status === 200 });
    if (!ok) errorRate.add(1);

    sleep(getRandomInt(1, 3));

    // 2. 투표 (쓰기)
    res = http.post(
        `${BASE_URL}/api/v1/entries/${entryId}/votes/test?memberId=${memberId}&strategy=async`,
        null,
        { tags: { name: 'vote', type: 'write' } }
    );
    voteTrend.add(res.timings.duration);

    if (res.status === 201) {
        voteSuccess.add(1);
        errorRate.add(false);
    } else if (res.status === 400) {
        // 중복 투표 or 자기 투표 — 비즈니스 에러 (시스템 에러 아님)
        voteDuplicate.add(1);
        errorRate.add(false);
    } else {
        // 5xx 등 실제 에러
        voteErrors.add(1);
        errorRate.add(true);
    }

    sleep(getRandomInt(1, 2));

    // 3. 랭킹 재확인 (투표 후 순위 변동 확인)
    res = http.get(`${BASE_URL}/api/v1/challenges/${challengeId}/ranking?limit=10`, {
        tags: { name: 'ranking', type: 'read' },
    });
    rankingTrend.add(res.timings.duration);
    ok = check(res, { 'ranking 200': (r) => r.status === 200 });
    errorRate.add(!ok);

    sleep(getRandomInt(1, 2));
}

// ============================================
// Main Test Function
// ============================================
export default function () {
    // 70% 구경, 30% 투표
    if (Math.random() < 0.7) {
        browseScenario();
    } else {
        voteScenario();
    }
}

// ============================================
// Summary
// ============================================
export function handleSummary(data) {
    const summary = {
        timestamp: new Date().toISOString(),
        thresholds: data.thresholds,
        metrics: {
            // 전체
            http_req_duration_p95: data.metrics.http_req_duration?.values?.['p(95)'],
            http_req_duration_p99: data.metrics.http_req_duration?.values?.['p(99)'],
            http_reqs_rate: data.metrics.http_reqs?.values?.rate,
            error_rate: data.metrics.errors?.values?.rate,

            // 읽기
            read_challenge_list_p95: data.metrics.read_challenge_list?.values?.['p(95)'],
            read_ranking_p95: data.metrics.read_ranking?.values?.['p(95)'],
            read_entries_p95: data.metrics.read_entries?.values?.['p(95)'],

            // 쓰기
            write_vote_p95: data.metrics.write_vote?.values?.['p(95)'],
            write_vote_p99: data.metrics.write_vote?.values?.['p(99)'],

            // 투표 결과
            vote_success: data.metrics.vote_success?.values?.count,
            vote_duplicate: data.metrics.vote_duplicate?.values?.count,
            vote_errors: data.metrics.vote_errors?.values?.count,
        },
    };

    // 콘솔에 요약 출력
    console.log('\n========== PetStar Mixed Load Test Results ==========');
    console.log(`Timestamp: ${summary.timestamp}`);
    console.log('');
    console.log('--- 전체 ---');
    console.log(`  HTTP Requests/sec: ${summary.metrics.http_reqs_rate?.toFixed(1)}`);
    console.log(`  전체 p95: ${summary.metrics.http_req_duration_p95?.toFixed(1)}ms`);
    console.log(`  전체 p99: ${summary.metrics.http_req_duration_p99?.toFixed(1)}ms`);
    console.log(`  에러율: ${(summary.metrics.error_rate * 100)?.toFixed(2)}%`);
    console.log('');
    console.log('--- 읽기 API (p95) ---');
    console.log(`  챌린지 목록: ${summary.metrics.read_challenge_list_p95?.toFixed(1)}ms`);
    console.log(`  랭킹:       ${summary.metrics.read_ranking_p95?.toFixed(1)}ms`);
    console.log(`  엔트리:     ${summary.metrics.read_entries_p95?.toFixed(1)}ms`);
    console.log('');
    console.log('--- 쓰기 API (p95) ---');
    console.log(`  투표:       ${summary.metrics.write_vote_p95?.toFixed(1)}ms`);
    console.log(`  투표 p99:   ${summary.metrics.write_vote_p99?.toFixed(1)}ms`);
    console.log('');
    console.log('--- 투표 결과 ---');
    console.log(`  성공:       ${summary.metrics.vote_success}`);
    console.log(`  중복(400):  ${summary.metrics.vote_duplicate}`);
    console.log(`  에러(5xx):  ${summary.metrics.vote_errors}`);
    console.log('====================================================\n');

    return {
        stdout: JSON.stringify(summary, null, 2),
        './k6/results/mixed-load-result.json': JSON.stringify(summary, null, 2),
    };
}