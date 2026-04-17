// Load Test — 일반 트래픽 시뮬레이션
// 목적: 평시 트래픽에서의 p95 응답시간, 에러율, 처리량 측정
// 실행: k6 run scripts/k6/load.js
//
// 시나리오: 1분 ramp-up → 5분 유지(50 VU) → 1분 ramp-down

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { pickRandomScenario } from './scenarios.js';

// 커스텀 메트릭
const routeDuration = new Trend('route_duration', true);
const routeErrors = new Rate('route_errors');
const routesReturned = new Counter('routes_returned');

export const options = {
  stages: [
    { duration: '1m', target: 50 },   // 1분간 50 VU까지 증가
    { duration: '5m', target: 50 },   // 5분간 50 VU 유지
    { duration: '1m', target: 0 },    // 1분간 감소
  ],
  thresholds: {
    // 자동차 제조사 운영 기준 가정
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],  // p95 < 2초, p99 < 5초
    http_req_failed: ['rate<0.02'],                     // 에러율 < 2%
    route_errors: ['rate<0.05'],                        // 경로 탐색 실패 < 5%
  },
};

export default function () {
  const { url } = pickRandomScenario();

  const res = http.get(url, { tags: { name: 'GET /api/routes' } });

  routeDuration.add(res.timings.duration);
  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has routes': (r) => {
      try {
        const body = JSON.parse(r.body);
        if (Array.isArray(body.routes)) {
          routesReturned.add(body.routes.length);
          return true;
        }
        return false;
      } catch {
        return false;
      }
    },
  });
  routeErrors.add(!ok);

  // 사용자 체감: 응답 후 1~3초 대기 (다음 요청)
  sleep(Math.random() * 2 + 1);
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
    'output/perf-load-summary.json': JSON.stringify(data, null, 2),
  };
}

function textSummary(data) {
  const metrics = data.metrics;
  const p95 = metrics.http_req_duration?.values?.['p(95)'] ?? 0;
  const p99 = metrics.http_req_duration?.values?.['p(99)'] ?? 0;
  const avg = metrics.http_req_duration?.values?.avg ?? 0;
  const failRate = metrics.http_req_failed?.values?.rate ?? 0;
  const totalReqs = metrics.http_reqs?.values?.count ?? 0;
  const rps = metrics.http_reqs?.values?.rate ?? 0;

  return `
=== Black Mamba Load Test Summary ===
총 요청: ${totalReqs}
RPS: ${rps.toFixed(1)}
평균 응답시간: ${avg.toFixed(0)}ms
p95: ${p95.toFixed(0)}ms
p99: ${p99.toFixed(0)}ms
실패율: ${(failRate * 100).toFixed(2)}%

임계값 결과:
- p95 < 2000ms: ${p95 < 2000 ? '✅ PASS' : '❌ FAIL'}
- p99 < 5000ms: ${p99 < 5000 ? '✅ PASS' : '❌ FAIL'}
- 실패율 < 2%:  ${failRate < 0.02 ? '✅ PASS' : '❌ FAIL'}
`;
}
