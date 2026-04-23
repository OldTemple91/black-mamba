/**
 * 프론트엔드 Main/Result 페이지 스크린샷 자동 캡처.
 *
 * 전제조건:
 *   - 백엔드: docker compose up -d --build app (port 8081)
 *   - 프론트: cd frontend && npm run dev (port 5173)
 *
 * 실행:
 *   node scripts/screenshots/capture-frontend.mjs
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = resolve(__dirname, '../../output/playwright');
mkdirSync(OUT_DIR, { recursive: true });

const FRONTEND = 'http://localhost:5173';
// 현실 시나리오: "역이 아닌 위치" 간 이동 (MaaS 의 진짜 가치 시연)
//   출발: 서초 아파트 단지 (37.4850, 127.0320) — 지하철역에서 500m+ 떨어짐
//   도착: 성수 카페거리 (37.5420, 127.0554) — 지하철역에서 도보 7~10분
// 실측: TRANSIT_WITH_BIKE 28분 vs TRANSIT_ONLY 36분 → 8분 단축
// 프론트는 "lat,lng" 문자열 입력 지원 (MainPage.jsx handleSearch)
const ORIGIN = '37.4850,127.0320';        // 서초 아파트 단지
const DESTINATION = '37.5420,127.0554';   // 성수 카페거리

const browser = await chromium.launch();
const context = await browser.newContext({
  locale: 'ko-KR',
  viewport: { width: 1280, height: 1800 },
});
const page = await context.newPage();

// ────────────────────────────────────────────────────────────
// 1. Main Search Page
// ────────────────────────────────────────────────────────────
console.log('[1/2] 메인 페이지 이동 + 네이버 맵 로드 대기');
await page.goto(FRONTEND, { waitUntil: 'networkidle', timeout: 30000 });
// 맵 타일 로드 여유
await page.waitForTimeout(4000);

console.log('  캡처: output/playwright/main-page.png');
await page.screenshot({
  path: resolve(OUT_DIR, 'main-page.png'),
  fullPage: false,
});

// ────────────────────────────────────────────────────────────
// 2. Route Result Page — 실제 자동완성 선택 → 탐색 → 결과 대기 → 캡처
// ────────────────────────────────────────────────────────────
console.log('[2/2] 경로 결과 시나리오');

// 출발지 좌표 입력 (프론트 MainPage 가 "lat,lng" 문자열 그대로 지원)
console.log(`  출발지 입력: "${ORIGIN}" (서초 아파트)`);
const originInput = page.getByPlaceholder('출발지를 입력하세요');
await originInput.click();
await originInput.fill(ORIGIN);
// 좌표 입력 후 자동완성이 뜨면 닫기 위해 외부 클릭 (드롭다운 닫힘)
await page.mouse.click(10, 10);
await page.waitForTimeout(300);

// 목적지 좌표 입력
console.log(`  목적지 입력: "${DESTINATION}" (성수 카페거리)`);
const destInput = page.getByPlaceholder('목적지를 입력하세요');
await destInput.click();
await destInput.fill(DESTINATION);
await page.mouse.click(10, 10);
await page.waitForTimeout(300);

// "최적 탐색" 해제 (개별 mobility 버튼을 활성화하기 위해)
console.log('  최적 탐색 토글 해제 → SPECIFIC 모드 진입');
await page.getByRole('button', { name: /최적 탐색/ }).click();
await page.waitForTimeout(300);

// Mobility 선택: 개인 전기자전거
console.log('  이동수단 선택: ⚡ 전기자전거');
await page.getByRole('button', { name: /전기자전거/ }).click();
await page.waitForTimeout(300);

// Preference: 시간 우선
console.log('  선호도 선택: ⚡ 시간 우선');
await page.getByRole('button', { name: /시간 우선/ }).click();
await page.waitForTimeout(300);

// 짧게 대기 후 탐색 버튼
console.log('  경로 탐색 버튼 클릭');
await page.getByRole('button', { name: '경로 탐색' }).click();

// 결과 페이지 URL 변경 + 결과 카드 로드 대기
await page.waitForURL(/\/routes/, { timeout: 10000 });
console.log('  결과 페이지 이동 완료, 경로 카드 로드 대기 (최대 60초)');
// 실제 경로 카드에 나타날 만한 텍스트로 대기
try {
  await page.waitForFunction(
    () => {
      const text = document.body.innerText;
      // 카드 보임 판정: "분" + ("원" 또는 "지하철" 또는 "도보")
      return /\d+분/.test(text)
        && (/\d[\d,]*원/.test(text) || text.includes('지하철') || text.includes('도보') || text.includes('환승'));
    },
    { timeout: 60000 }
  );
  console.log('  결과 카드 렌더링 감지');
} catch (e) {
  console.log(`  (카드 대기 타임아웃 — 현 상태 캡처: ${e.message})`);
}
await page.waitForTimeout(4000);  // 지도 라인 + 타일 여유

console.log('  캡처: output/playwright/routes-page.png (fullPage)');
await page.screenshot({
  path: resolve(OUT_DIR, 'routes-page.png'),
  fullPage: true,
});

await browser.close();
console.log('\n✅ 프론트 캡처 완료');
