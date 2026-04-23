/**
 * 데모 비디오 (webm) 녹화.
 *
 * 시나리오:
 *   1. 메인 페이지 로드 (Hero motion 등장)
 *   2. 테마 토글 (라이트 → 다크)
 *   3. 다시 라이트
 *   4. 출발/목적지 입력
 *   5. Mobility + Preference 선택
 *   6. 날씨 선택 (RAIN)
 *   7. 경로 탐색 클릭
 *   8. 결과 페이지 로드 (skeleton → 실제 카드)
 *   9. 카드 클릭 시 지도 업데이트
 *
 * 실행:
 *   node scripts/screenshots/capture-demo-video.mjs
 *   → output/playwright/demo.webm
 *
 * GIF 변환 (ffmpeg 필요):
 *   ffmpeg -i output/playwright/demo.webm \
 *     -vf "fps=12,scale=900:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer" \
 *     -loop 0 output/playwright/demo.gif
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = resolve(__dirname, '../../output/playwright');
mkdirSync(OUT_DIR, { recursive: true });

const FRONTEND = 'http://localhost:5173';
const ORIGIN = '37.4850,127.0320';
const DESTINATION = '37.5420,127.0554';

const browser = await chromium.launch();
const context = await browser.newContext({
  locale: 'ko-KR',
  viewport: { width: 1280, height: 820 },
  recordVideo: {
    dir: OUT_DIR,
    size: { width: 1280, height: 820 },
  },
});

const page = await context.newPage();

// Step 1: 메인 로드
console.log('1. 메인 페이지 로드 + Hero 애니메이션');
await page.goto(FRONTEND, { waitUntil: 'networkidle' });
await page.waitForTimeout(2500);

// Step 2: 테마 토글
console.log('2. 라이트 → 다크 토글');
const themeBtn = page.locator('button[title*="모드"]').first();
await themeBtn.click();
await page.waitForTimeout(1600);

// Step 3: 다시 라이트
console.log('3. 다크 → 라이트 토글');
await themeBtn.click();
await page.waitForTimeout(1200);

// Step 4: 출발 입력
console.log('4. 출발지 입력');
const originInput = page.getByPlaceholder(/출발지/);
await originInput.click();
await originInput.fill(ORIGIN);
await page.mouse.click(10, 10);
await page.waitForTimeout(500);

// Step 5: 목적지 입력
console.log('5. 목적지 입력');
const destInput = page.getByPlaceholder(/목적지/);
await destInput.click();
await destInput.fill(DESTINATION);
await page.mouse.click(10, 10);
await page.waitForTimeout(500);

// Step 6: SPECIFIC + mobility + preference
console.log('6. 최적탐색 해제 + 전기자전거 + 시간 우선');
await page.getByRole('button', { name: /최적 탐색/ }).click();
await page.waitForTimeout(400);
await page.getByRole('button', { name: /전기자전거/ }).click();
await page.waitForTimeout(400);
await page.getByRole('button', { name: /시간 우선/ }).click();
await page.waitForTimeout(600);

// Step 7: RAIN 날씨
console.log('7. 날씨 RAIN 선택');
await page.getByRole('button', { name: '🌧 비' }).click();
await page.waitForTimeout(800);

// Step 8: 탐색
console.log('8. 경로 탐색 클릭');
await page.getByRole('button', { name: '경로 탐색' }).click();
await page.waitForURL(/\/routes/);

// Step 9: 결과 렌더링 대기
console.log('9. 결과 카드 로드');
try {
  await page.waitForFunction(
    () => /\d+분/.test(document.body.innerText) && document.body.innerText.includes('CO'),
    { timeout: 60000 }
  );
} catch (e) {
  console.log('  (로드 타임아웃)');
}
await page.waitForTimeout(2500);

// Step 10: 두 번째 카드 클릭 (지도 업데이트)
console.log('10. 2번째 카드 클릭');
const cards = page.locator('[class*="recommended-ring"], [class*="border-slate-200"][class*="bg-white"]').filter({ hasText: /\d+분/ });
const count = await cards.count();
if (count >= 2) {
  await cards.nth(1).click();
  await page.waitForTimeout(1500);
}

// Step 11: 스크롤 다운
console.log('11. 스크롤 다운');
await page.evaluate(() => window.scrollBy({ top: 600, behavior: 'smooth' }));
await page.waitForTimeout(1500);

// Step 12: 다크 토글
console.log('12. 결과 페이지 다크 모드');
const themeBtn2 = page.locator('button[title*="모드"]').first();
await themeBtn2.click();
await page.waitForTimeout(2000);

await context.close();
await browser.close();

console.log('\n✅ 데모 비디오 녹화 완료 (output/playwright/*.webm)');
console.log('   GIF 변환:');
console.log('   ffmpeg -i output/playwright/<video>.webm \\');
console.log('     -vf "fps=12,scale=900:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse" \\');
console.log('     -loop 0 output/playwright/demo.gif');
