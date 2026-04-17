// B-3 검증용: 공간적으로 가까운 좌표를 흔들어서 캐시 히트율 측정
// 각 기준 OD를 중심으로 ±0.0005 (약 50m) 범위로 jitter 추가
//
// Before (좌표 기반 RouteKey): 대부분 miss
// After  (Geohash 기반 키):   대부분 hit

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL } from './scenarios.js';

const BASE_OD_PAIRS = [
  { name: '강남→홍대', oLat: 37.4979, oLng: 127.0276, dLat: 37.5573, dLng: 126.9246 },
  { name: '수서→이태원', oLat: 37.4872, oLng: 127.1016, dLat: 37.5345, dLng: 126.9946 },
  { name: '여의도→서울숲', oLat: 37.5264, oLng: 126.9343, dLat: 37.5445, dLng: 127.0374 },
];

// ±0.0005도 ≈ 위도 55m, 경도 44m (서울 기준) — Geohash precision 7(~150m) 안쪽
function jitter(v) {
  return v + (Math.random() - 0.5) * 0.001;
}

export const options = {
  vus: 1,
  duration: '90s',
};

export default function () {
  const base = BASE_OD_PAIRS[__ITER % BASE_OD_PAIRS.length];
  const url = `${BASE_URL}/api/routes`
    + `?originLat=${jitter(base.oLat).toFixed(6)}`
    + `&originLng=${jitter(base.oLng).toFixed(6)}`
    + `&destLat=${jitter(base.dLat).toFixed(6)}`
    + `&destLng=${jitter(base.dLng).toFixed(6)}`
    + `&searchMode=OPTIMAL`;

  const res = http.get(url);
  check(res, { 'status 200': (r) => r.status === 200 });
  sleep(1);
}
