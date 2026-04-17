// Stress Test — 한계 탐색
// 목적: 서버가 몇 RPS까지 p95 2초를 유지하는지, 어디서 무너지는지 관찰
// 실행: k6 run scripts/k6/stress.js
//
// 시나리오: 50 → 100 → 200 VU로 단계적 증가, 각 3분씩 유지

import http from 'k6/http';
import { check, sleep } from 'k6';
import { pickRandomScenario } from './scenarios.js';

export const options = {
  stages: [
    { duration: '2m', target: 50 },
    { duration: '3m', target: 100 },
    { duration: '3m', target: 200 },
    { duration: '2m', target: 0 },
  ],
  thresholds: {
    // Stress에서는 느슨한 기준 (한계 관찰용)
    http_req_failed: ['rate<0.10'],
  },
};

export default function () {
  const { url } = pickRandomScenario();
  const res = http.get(url);

  check(res, {
    'status 200': (r) => r.status === 200,
    'not 5xx':    (r) => r.status < 500,
  });

  sleep(Math.random() * 1.5 + 0.5);
}
