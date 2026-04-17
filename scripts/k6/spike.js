// Spike Test — 급증 트래픽 대응
// 목적: 평시 대비 6배 급증 시 서버가 회복 가능한지 확인
// 실행: k6 run scripts/k6/spike.js
//
// 시나리오: 정상 50 VU → 급증 300 VU (30초) → 정상 복귀

import http from 'k6/http';
import { check, sleep } from 'k6';
import { pickRandomScenario } from './scenarios.js';

export const options = {
  stages: [
    { duration: '1m',  target: 50 },   // 평시
    { duration: '30s', target: 300 },  // 급증
    { duration: '1m',  target: 50 },   // 정상 복귀
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    // 급증 구간 허용 + 평시 복구 검증
    http_req_failed: ['rate<0.15'],
  },
};

export default function () {
  const { url } = pickRandomScenario();
  const res = http.get(url);
  check(res, { 'status not 5xx': (r) => r.status < 500 });
  sleep(1);
}
