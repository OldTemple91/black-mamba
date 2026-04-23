/**
 * 모바일 뷰 (iPhone 14 viewport) 스크린샷 캡처.
 * - 라이트 / 다크 모두
 * - 메인 + 결과 페이지
 *
 * 실행:
 *   node scripts/screenshots/capture-mobile.mjs
 */
import { chromium, devices } from 'playwright';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = resolve(__dirname, '../../output/playwright');
mkdirSync(OUT_DIR, { recursive: true });

const FRONTEND = 'http://localhost:5173';
const ORIGIN = '37.4850,127.0320';
const DESTINATION = '37.5420,127.0554';

async function captureMobile({ theme, outMain, outRoutes }) {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    ...devices['iPhone 14'],
    locale: 'ko-KR',
    colorScheme: theme === 'dark' ? 'dark' : 'light',
  });

  if (theme === 'dark') {
    await context.addInitScript(() => {
      window.localStorage.setItem('bm:theme', 'dark');
    });
  } else {
    await context.addInitScript(() => {
      window.localStorage.setItem('bm:theme', 'light');
    });
  }

  const page = await context.newPage();

  console.log(`[Mobile ${theme}] 메인`);
  await page.goto(FRONTEND, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(3500);
  await page.screenshot({ path: resolve(OUT_DIR, outMain), fullPage: true });
  console.log(`  → ${outMain}`);

  // 결과 페이지
  console.log(`[Mobile ${theme}] 결과`);
  const originInput = page.getByPlaceholder(/출발지/);
  await originInput.click();
  await originInput.fill(ORIGIN);
  await page.mouse.click(10, 10);
  await page.waitForTimeout(200);

  const destInput = page.getByPlaceholder(/목적지/);
  await destInput.click();
  await destInput.fill(DESTINATION);
  await page.mouse.click(10, 10);
  await page.waitForTimeout(200);

  await page.getByRole('button', { name: /최적 탐색/ }).click();
  await page.waitForTimeout(200);
  await page.getByRole('button', { name: /전기자전거/ }).click();
  await page.waitForTimeout(200);
  await page.getByRole('button', { name: /시간 우선/ }).click();
  await page.waitForTimeout(200);
  await page.getByRole('button', { name: '경로 탐색' }).click();
  await page.waitForURL(/\/routes/, { timeout: 10000 });

  try {
    await page.waitForFunction(
      () => /\d+분/.test(document.body.innerText) && document.body.innerText.includes('CO'),
      { timeout: 60000 }
    );
  } catch (e) {
    console.log(`  (타임아웃)`);
  }
  await page.waitForTimeout(3000);
  await page.screenshot({ path: resolve(OUT_DIR, outRoutes), fullPage: true });
  console.log(`  → ${outRoutes}`);

  await browser.close();
}

await captureMobile({
  theme: 'light',
  outMain: 'mobile-main-light.png',
  outRoutes: 'mobile-routes-light.png',
});
await captureMobile({
  theme: 'dark',
  outMain: 'mobile-main-dark.png',
  outRoutes: 'mobile-routes-dark.png',
});

console.log('\n✅ 모바일 4종 캡처 완료');
