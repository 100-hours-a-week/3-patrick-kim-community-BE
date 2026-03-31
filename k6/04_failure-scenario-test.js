/**
 * PetStar 장애 시나리오 테스트 (Phase 11)
 *
 * 목적: 외부 서비스(SQS, Redis) 장애 시 Graceful Degradation 검증
 *
 * 사용법:
 *   # 정상 상태 베이스라인
 *   k6 run --env SCENARIO=baseline 04_failure-scenario-test.js
 *
 *   # SQS 장애 시나리오 (큐 이름을 잘못 설정한 상태에서)
 *   k6 run --env SCENARIO=sqs_failure 04_failure-scenario-test.js
 *
 *   # Redis 장애 시나리오 (Redis 중지한 상태에서)
 *   k6 run --env SCENARIO=redis_failure 04_failure-scenario-test.js
 *
 *   # 둘 다 장애 시나리오
 *   k6 run --env SCENARIO=both_failure 04_failure-scenario-test.js
 *
 * 테스트 조건: 50 VUs, 30초 (빠른 검증용)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// Custom Metrics
const voteTrend = new Trend('vote_duration', true);
const voteSuccessRate = new Rate('vote_success');
const voteCount = new Counter('vote_total');
const voteFailCount = new Counter('vote_fail');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'baseline';
const CHALLENGE_ID = 11; // Active challenge

// 테스트 설정: 50 VUs, 30초
export const options = {
    scenarios: {
        vote_test: {
            executor: 'constant-vus',
            vus: 50,
            duration: '30s',
        },
    },
    thresholds: {
        // 장애 시나리오에서는 임계치를 느슨하게
        'vote_duration': ['p(95)<5000'],
    },
};

export default function () {
    const vuId = __VU;
    const iter = __ITER;

    // 각 VU마다 다른 memberId 사용 (중복 투표 방지)
    const memberId = 500 + vuId * 100 + iter;

    // 다양한 Entry에 투표 (Hot Spot 시뮬레이션)
    const entryId = getRandomEntryId();

    // 투표 API 호출
    const url = `${BASE_URL}/api/v1/entries/${entryId}/votes/test?memberId=${memberId}&strategy=async`;
    const startTime = new Date();

    const res = http.post(url);

    const duration = new Date() - startTime;
    voteTrend.add(duration);
    voteCount.add(1);

    const success = check(res, {
        'vote status is 2xx': (r) => r.status >= 200 && r.status < 300,
        'vote response has result': (r) => {
            try {
                const body = JSON.parse(r.body);
                return body.isSuccess === true || body.result !== undefined;
            } catch (e) {
                return false;
            }
        },
    });

    if (success) {
        voteSuccessRate.add(1);
    } else {
        voteSuccessRate.add(0);
        voteFailCount.add(1);

        // 실패 시 응답 로깅 (처음 몇 개만)
        if (iter < 3) {
            console.log(`[${SCENARIO}] Vote FAILED: status=${res.status}, body=${res.body}`);
        }
    }

    sleep(0.5); // 0.5초 간격
}

function getRandomEntryId() {
    // Hot Spot: 50% 확률로 상위 5개 Entry
    if (Math.random() < 0.5) {
        return Math.floor(Math.random() * 5) + 1; // entryId 1~5
    } else {
        return Math.floor(Math.random() * 100) + 6; // entryId 6~105
    }
}

export function handleSummary(data) {
    const p95 = data.metrics.vote_duration ? data.metrics.vote_duration.values['p(95)'] : 'N/A';
    const avg = data.metrics.vote_duration ? data.metrics.vote_duration.values['avg'] : 'N/A';
    const successRate = data.metrics.vote_success ? data.metrics.vote_success.values['rate'] : 'N/A';
    const total = data.metrics.vote_total ? data.metrics.vote_total.values['count'] : 0;
    const fails = data.metrics.vote_fail ? data.metrics.vote_fail.values['count'] : 0;

    const summary = `
=== Phase 11 장애 시나리오 테스트 결과 ===
시나리오: ${SCENARIO}
VUs: 50, Duration: 30s

| 지표 | 값 |
|------|------|
| 총 요청 | ${total} |
| 성공률 | ${(successRate * 100).toFixed(1)}% |
| 실패 수 | ${fails} |
| avg 응답시간 | ${typeof avg === 'number' ? avg.toFixed(0) : avg}ms |
| p95 응답시간 | ${typeof p95 === 'number' ? p95.toFixed(0) : p95}ms |
==========================================
`;

    console.log(summary);

    return {
        'stdout': summary,
    };
}
