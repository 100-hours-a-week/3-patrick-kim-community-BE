/**
 * PetStar 기본 부하테스트 스크립트
 *
 * 시나리오: 일반 사용자 행동 패턴 시뮬레이션
 *   1. 챌린지 목록 조회 (홈 화면)
 *   2. 참여작 목록 조회 (챌린지 상세)
 *   3. 랭킹 조회 (가장 부하 높은 API)
 *
 * 가정:
 *   - 사용자는 1~2초 간격으로 페이지를 탐색
 *   - 30개의 ACTIVE 챌린지 중 랜덤 선택
 *   - 페이지당 10개 항목 조회 (기본값)
 *
 * 사용:
 *   k6 run --vus 10 --duration 1m 01_basic-load-test.js
 *   k6 run --vus 50 --duration 2m 01_basic-load-test.js
 *   k6 run --vus 100 --duration 2m 01_basic-load-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ============================================
// Custom Metrics (API별 응답시간 측정)
// ============================================
var errorRate = new Rate('errors');
var challengeListTrend = new Trend('challenge_list_duration', true);
var entriesTrend = new Trend('entries_list_duration', true);
var rankingTrend = new Trend('ranking_duration', true);

// ============================================
// Configuration
// ============================================
var BASE_URL = __ENV.BASE_URL || 'http://43.200.83.214:8080';

// ACTIVE 상태 챌린지 ID (시딩 데이터 기준: 11~40)
var CHALLENGE_IDS = [
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
    11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
    21, 22, 23, 24, 25, 26, 27, 28, 29, 30
];

// ============================================
// Test Options (기본값: smoke test)
// ============================================
export var options = {
    vus: 10,
    duration: '1m',
    thresholds: {
        http_req_duration: ['p(95)<2000'],  // p95 < 2초
        errors: ['rate<0.1'],                // 에러율 < 10%
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

// ============================================
// Test Scenario
// ============================================
export default function () {
    var challengeId = getRandomElement(CHALLENGE_IDS);

    // 1. 챌린지 목록 조회 (홈 화면)
    //    - 사용자가 앱 진입 시 가장 먼저 보는 화면
    //    - 페이징 없이 기본 목록 조회
    var challengeListRes = http.get(BASE_URL + '/api/v1/challenges', {
        tags: { name: 'challenge_list' },
    });
    challengeListTrend.add(challengeListRes.timings.duration);
    var challengeListOk = check(challengeListRes, {
        'challenge list: status 200': function(r) { return r.status === 200; },
    });
    if (!challengeListOk) errorRate.add(1);

    // 사용자 탐색 시간 (1~2초)
    sleep(getRandomInt(1, 2));

    // 2. 참여작 목록 조회 (챌린지 상세)
    //    - 특정 챌린지 클릭 후 참여작 목록 확인
    //    - 페이징: limit=10 (기본값)
    var entriesRes = http.get(
        BASE_URL + '/api/v1/challenges/' + challengeId + '/entries?limit=10',
        { tags: { name: 'entries_list' } }
    );
    entriesTrend.add(entriesRes.timings.duration);
    var entriesOk = check(entriesRes, {
        'entries list: status 200': function(r) { return r.status === 200; },
    });
    if (!entriesOk) errorRate.add(1);

    sleep(getRandomInt(1, 2));

    // 3. 랭킹 조회 (가장 부하 높은 API)
    //    - ORDER BY vote_count DESC 필요
    //    - Entry + Pet + Image JOIN 필요
    //    - N+1 문제 발생 가능 지점
    var rankingRes = http.get(
        BASE_URL + '/api/v1/challenges/' + challengeId + '/ranking?limit=10',
        { tags: { name: 'ranking' } }
    );
    rankingTrend.add(rankingRes.timings.duration);
    var rankingOk = check(rankingRes, {
        'ranking: status 200': function(r) { return r.status === 200; },
    });
    if (!rankingOk) errorRate.add(1);

    sleep(getRandomInt(1, 2));
}
