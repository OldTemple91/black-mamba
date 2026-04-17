// Smoke Test — 1 VU, 30초
// 목적: 서버가 정상 응답하는지만 빠르게 확인
// 실행: k6 run scripts/k6/smoke.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, OD_PAIRS, buildRouteUrl } from './scenarios.js';

export const options = {
  vus: 1,
  duration: '30s',
  thresholds: {
    http_req_duration: ['p(95)<3000'],  // p95 < 3초
    http_req_failed: ['rate<0.01'],      // 에러율 < 1%
  },
};

export default function () {
  // 헬스체크
  const health = http.get(`${BASE_URL}/actuator/health`);
  check(health, { 'health 200': (r) => r.status === 200 });

  // 경로 탐색
  const od = OD_PAIRS[__ITER % OD_PAIRS.length];
  const url = buildRouteUrl(od, { mode: 'OPTIMAL', pref: 'RELIABILITY' });
  const res = http.get(url);

  check(res, {
    'route 200': (r) => r.status === 200,
    'has routes': (r) => {
      try {
        const body = JSON.parse(r.body);
        return Array.isArray(body.routes);
      } catch {
        return false;
      }
    },
  });

  sleep(1);
}
