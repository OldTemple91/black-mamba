// 공통 테스트 시나리오 — 서울 주요 OD 쌍
// 각 부하 테스트 스크립트에서 import하여 사용

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

// 다양한 거리/지역 분포로 캐시 hit율 + 실사용 패턴 시뮬레이션
export const OD_PAIRS = [
  {
    name: '강남→홍대 (도심 통근, 직선 8km)',
    originLat: 37.4979, originLng: 127.0276,
    destLat: 37.5573,   destLng: 126.9246,
  },
  {
    name: '수서→이태원 (외곽→시내, 직선 7km)',
    originLat: 37.4872, originLng: 127.1016,
    destLat: 37.5345,   destLng: 126.9946,
  },
  {
    name: '여의도→서울숲 (중거리, 직선 9km)',
    originLat: 37.5264, originLng: 126.9343,
    destLat: 37.5445,   destLng: 127.0374,
  },
  {
    name: '강남→잠실 (짧은 도심, 직선 5km)',
    originLat: 37.4979, originLng: 127.0276,
    destLat: 37.5133,   destLng: 127.1000,
  },
  {
    name: '신촌→성수 (도심 횡단, 직선 8km)',
    originLat: 37.5556, originLng: 126.9365,
    destLat: 37.5447,   destLng: 127.0557,
  },
];

export const SEARCH_MODES = ['OPTIMAL', 'SPECIFIC'];
export const PREFERENCES = ['RELIABILITY', 'TIME_PRIORITY'];
export const MOBILITY_TYPES = ['DDAREUNGI', 'PERSONAL_EBIKE', 'PERSONAL_KICKBOARD'];

/**
 * OD 쌍 + 검색 조건을 조합한 요청 URL 생성
 */
export function buildRouteUrl(od, { mode = 'OPTIMAL', pref = 'RELIABILITY', mobility = [] } = {}) {
  const params = new URLSearchParams({
    originLat: od.originLat,
    originLng: od.originLng,
    destLat: od.destLat,
    destLng: od.destLng,
    searchMode: mode,
    recommendationPreference: pref,
  });
  if (mobility.length > 0) {
    params.append('mobility', mobility.join(','));
  }
  return `${BASE_URL}/api/routes?${params.toString()}`;
}

/**
 * 랜덤하게 OD + 조건 조합 선택 (다양성 확보)
 */
export function pickRandomScenario() {
  const od = OD_PAIRS[Math.floor(Math.random() * OD_PAIRS.length)];
  const mode = SEARCH_MODES[Math.floor(Math.random() * SEARCH_MODES.length)];
  const pref = PREFERENCES[Math.floor(Math.random() * PREFERENCES.length)];
  const mobility = mode === 'SPECIFIC'
    ? [MOBILITY_TYPES[Math.floor(Math.random() * MOBILITY_TYPES.length)]]
    : [];
  return { od, url: buildRouteUrl(od, { mode, pref, mobility }), mode, pref };
}
