import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

// Custom metrics
const voteResponseTime = new Trend('vote_response_time', true);
const voteErrorRate = new Rate('vote_errors');
const voteSuccessCount = new Counter('vote_success_count');

// Configuration
const BASE_URL = __ENV.BASE_URL || 'http://43.200.83.214:8080';

// Test options - 50 VUs 동시 투표
export const options = {
  scenarios: {
    concurrent_votes: {
      executor: 'per-vu-iterations',
      vus: 50,
      iterations: 1,
      maxDuration: '60s',
    },
  },
  thresholds: {
    vote_response_time: ['p(95)<100'],  // p95 < 100ms 목표 (비동기 방식)
    vote_errors: ['rate<0.01'],         // 에러율 1% 미만
  },
};

// 50명이 동시에 같은 Entry에 투표 (Hot Spot 시뮬레이션)
export default function () {
  const vuId = __VU;

  // 테스트용 Member ID와 Entry ID
  // Member ID는 128~177 (자신의 Entry가 아닌 곳에 투표)
  // Entry 100의 소유자는 member_id 227이므로 128~177이 투표 가능
  const memberId = 127 + vuId;  // member_id 128~177
  const entryId = 100;  // 모든 VU가 같은 Entry에 투표 (Hot Spot)

  // 테스트 엔드포인트 사용 (인증 없이 접근 가능)
  // strategy=async로 SQS 비동기 투표 방식 사용
  const voteStart = Date.now();
  const voteRes = http.post(
    `${BASE_URL}/api/v1/entries/${entryId}/votes/test?memberId=${memberId}&strategy=async`,
    null,
    {
      headers: {
        'Content-Type': 'application/json',
      },
    }
  );
  const voteEnd = Date.now();
  const voteDuration = voteEnd - voteStart;

  voteResponseTime.add(voteDuration);

  const voteOk = check(voteRes, {
    'vote: status 200 or 201': (r) => r.status === 200 || r.status === 201,
    'vote: success response': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.isSuccess === true;
      } catch (e) {
        return false;
      }
    },
  });

  if (voteOk) {
    voteSuccessCount.add(1);
    console.log(`VU ${vuId}: Vote success in ${voteDuration}ms`);
  } else {
    voteErrorRate.add(1);
    console.error(`VU ${vuId}: Vote failed - ${voteRes.status} ${voteRes.body}`);
  }
}
