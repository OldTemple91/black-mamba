/**
 * Dark Mode 스크린샷 캡처.
 * localStorage 에 bm:theme=dark 를 세팅 후 페이지 로드해 다크 테마 전체 뷰 촬영.
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
  viewport: { width: 1280, height: 1800 },
  colorScheme: 'dark',
});

// localStorage 에 다크모드 플래그 선주입
await context.addInitScript(() => {
  window.localStorage.setItem('bm:theme', 'dark');
});

const page = await context.newPage();

console.log('[Dark 1/2] 메인 페이지 (다크)');
await page.goto(FRONTEND, { waitUntil: 'networkidle', timeout: 30000 });
await page.waitForTimeout(3500);
await page.screenshot({
  path: resolve(OUT_DIR, 'main-page-dark.png'),
  fullPage: false,
});
console.log('  → main-page-dark.png');

console.log('[Dark 2/2] 결과 페이지 (다크)');
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
  console.log(`  (타임아웃: ${e.message})`);
}
await page.waitForTimeout(3000);

await page.screenshot({
  path: resolve(OUT_DIR, 'routes-page-dark.png'),
  fullPage: true,
});
console.log('  → routes-page-dark.png');

await browser.close();
console.log('\n✅ 다크 모드 캡처 완료');
