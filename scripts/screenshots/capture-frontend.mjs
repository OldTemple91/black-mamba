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
// 대표 OD: 서울역 → 강남역 (대중교통 직행 경로가 확실히 나옴)
const ORIGIN = '서울역';
const DESTINATION = '강남역';

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

// 출발지 입력
console.log(`  출발지 입력: "${ORIGIN}"`);
const originInput = page.getByPlaceholder('출발지를 입력하세요');
await originInput.click();
await originInput.fill(ORIGIN);
// 자동완성 드롭다운 첫 항목 클릭 (좌표 설정 목적)
console.log('  자동완성 대기 + 첫 항목 선택');
try {
  await page.waitForSelector('ul li', { timeout: 5000, state: 'visible' });
  await page.locator('ul li').first().click();
} catch (e) {
  console.log(`  (자동완성 실패 — 입력값 그대로 진행: ${e.message})`);
}

// 목적지 입력
console.log(`  목적지 입력: "${DESTINATION}"`);
const destInput = page.getByPlaceholder('목적지를 입력하세요');
await destInput.click();
await destInput.fill(DESTINATION);
try {
  await page.waitForSelector('ul li', { timeout: 5000, state: 'visible' });
  await page.locator('ul li').first().click();
} catch (e) {
  console.log(`  (자동완성 실패 — 입력값 그대로 진행: ${e.message})`);
}

// 짧게 대기 후 탐색 버튼
await page.waitForTimeout(500);
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
