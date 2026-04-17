// 에러 케이스 시뮬레이션
// 목적: 400, 404, 500 등 다양한 에러를 발생시켜 Loki 에러 로그 + 트레이스 실패 사례 생성
// 실행: ./scripts/k6/run.sh error-cases

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, buildRouteUrl } from './scenarios.js';

const errorsByCode = new Counter('errors_by_status');

export const options = {
  vus: 1,
  duration: '1m',
};

const ERROR_CASES = [
  {
    name: '위도 범위 초과',
    url: `${BASE_URL}/api/routes?originLat=999&originLng=127.0&destLat=37.5&destLng=127.0`,
    expected: 400,
  },
  {
    name: '경도 범위 초과',
    url: `${BASE_URL}/api/routes?originLat=37.5&originLng=-999&destLat=37.6&destLng=127.0`,
    expected: 400,
  },
  {
    name: '단거리 (700m 이내)',
    url: `${BASE_URL}/api/routes?originLat=37.5665&originLng=126.9780&destLat=37.5680&destLng=126.9785`,
    expected: 400,
  },
  {
    name: '필수 파라미터 누락',
    url: `${BASE_URL}/api/routes?originLat=37.5547`,
    expected: 400,
  },
  {
    name: '잘못된 mobility 타입',
    url: `${BASE_URL}/api/routes?originLat=37.4979&originLng=127.0276&destLat=37.5573&destLng=126.9246&mobility=INVALID_TYPE`,
    expected: 400,
  },
  {
    name: '존재하지 않는 엔드포인트',
    url: `${BASE_URL}/api/nonexistent`,
    expected: 404,
  },
  {
    name: '바다 한복판 좌표 (정상 범위지만 결과 비정상)',
    url: `${BASE_URL}/api/routes?originLat=33.0&originLng=126.0&destLat=34.0&destLng=125.0`,
    expected: [200, 400, 500],  // 환경에 따라 다름
  },
];

export default function () {
  const testCase = ERROR_CASES[__ITER % ERROR_CASES.length];
  const res = http.get(testCase.url);

  const expectedCodes = Array.isArray(testCase.expected) ? testCase.expected : [testCase.expected];
  const ok = check(res, {
    [`${testCase.name} → 예상 상태코드 (${expectedCodes.join('/')}) 반환`]:
      (r) => expectedCodes.includes(r.status),
  });

  errorsByCode.add(1, { status: String(res.status), case: testCase.name });

  console.log(`[${testCase.name}] HTTP ${res.status} → 예상 ${expectedCodes.join('/')}`);

  sleep(1);
}
