import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
var errorRate = new Rate('errors');
var challengeListTrend = new Trend('challenge_list_duration', true);
var entriesTrend = new Trend('entries_list_duration', true);
var rankingTrend = new Trend('ranking_duration', true);

// Configuration
var BASE_URL = __ENV.BASE_URL || 'http://43.200.83.214:8080';

// Test options
export var options = {
  vus: 10,
  duration: '1m',
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    errors: ['rate<0.1'],
  },
};

// Challenge IDs (1~30 ACTIVE)
var CHALLENGE_IDS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30];

function getRandomElement(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function getRandomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

export default function () {
  var challengeId = getRandomElement(CHALLENGE_IDS);

  // 1. 챌린지 목록 조회
  var challengeListRes = http.get(BASE_URL + '/api/v1/challenges', {
    tags: { name: 'challenge_list' },
  });
  challengeListTrend.add(challengeListRes.timings.duration);
  var challengeListOk = check(challengeListRes, {
    'challenge list: status 200': function(r) { return r.status === 200; },
  });
  if (!challengeListOk) errorRate.add(1);

  sleep(getRandomInt(1, 2));

  // 2. 참여작 목록 조회 (페이징)
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

  // 3. 랭킹 조회
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