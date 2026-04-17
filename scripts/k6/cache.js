// Cache Hit Rate Test — 캐시 효과 검증
// 목적: 동일 OD 반복 호출 시 cold vs warm 응답시간 차이 측정
// 실행: k6 run scripts/k6/cache.js
//
// 시나리오: 하나의 OD 쌍으로 10회 반복 (Warmup 1 + 측정 9)

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, OD_PAIRS, buildRouteUrl } from './scenarios.js';

const coldDuration = new Trend('cold_duration_ms', true);
const warmDuration = new Trend('warm_duration_ms', true);

export const options = {
  vus: 1,
  iterations: 1,  // 시나리오 1회만 수동 제어
};

export default function () {
  console.log('=== 캐시 효과 측정 시작 ===');

  OD_PAIRS.forEach((od, idx) => {
    const url = buildRouteUrl(od, { mode: 'OPTIMAL', pref: 'RELIABILITY' });

    // Cold (첫 요청)
    const cold = http.get(url);
    check(cold, { 'cold 200': (r) => r.status === 200 });
    coldDuration.add(cold.timings.duration);
    console.log(`[${od.name}] Cold: ${cold.timings.duration.toFixed(0)}ms`);

    // Warm (같은 요청 3회 반복)
    for (let i = 0; i < 3; i++) {
      const warm = http.get(url);
      check(warm, { 'warm 200': (r) => r.status === 200 });
      warmDuration.add(warm.timings.duration);
      console.log(`[${od.name}] Warm ${i + 1}: ${warm.timings.duration.toFixed(0)}ms`);
    }

    // 캐시 메트릭 조회
    const cacheMetric = http.get(`${BASE_URL}/actuator/metrics/navigation.cache.total`);
    if (cacheMetric.status === 200) {
      try {
        const body = JSON.parse(cacheMetric.body);
        console.log(`  캐시 메트릭: ${JSON.stringify(body.measurements)}`);
      } catch {}
    }
  });
}

export function handleSummary(data) {
  const coldAvg = data.metrics.cold_duration_ms?.values?.avg ?? 0;
  const warmAvg = data.metrics.warm_duration_ms?.values?.avg ?? 0;
  const improvement = coldAvg > 0 ? ((1 - warmAvg / coldAvg) * 100) : 0;

  const summary = `
=== 캐시 효과 분석 ===
Cold 평균: ${coldAvg.toFixed(0)}ms
Warm 평균: ${warmAvg.toFixed(0)}ms
응답시간 개선: ${improvement.toFixed(1)}%
`;
  return {
    stdout: summary,
    'output/perf-cache-summary.json': JSON.stringify(data, null, 2),
  };
}
