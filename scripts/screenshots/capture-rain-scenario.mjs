/**
 * A-4 Weather-aware Routing 시연용 캡처.
 *
 * CLEAR vs RAIN 을 같은 OD 로 비교 — RAIN 에서 공유 자전거 스코어가 감점되어
 * 순위·배지에 반영되는지 시각 증거.
 *
 * 실행:
 *   node scripts/screenshots/capture-rain-scenario.mjs
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = resolve(__dirname, '../../output/playwright');
mkdirSync(OUT_DIR, { recursive: true });

const FRONTEND = 'http://localhost:5173';
const ORIGIN = '37.4850,127.0320';        // 서초 아파트
const DESTINATION = '37.5420,127.0554';   // 성수 카페거리

async function captureScenario(weatherKey, weatherLabel, outputName) {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    locale: 'ko-KR',
    viewport: { width: 1280, height: 1800 },
  });
  const page = await context.newPage();

  console.log(`[${weatherLabel}] 메인 페이지 → 입력 → 날씨 선택 → 탐색`);
  await page.goto(FRONTEND, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(2000);

  const originInput = page.getByPlaceholder('출발지를 입력하세요');
  await originInput.click();
  await originInput.fill(ORIGIN);
  await page.mouse.click(10, 10);
  await page.waitForTimeout(200);

  const destInput = page.getByPlaceholder('목적지를 입력하세요');
  await destInput.click();
  await destInput.fill(DESTINATION);
  await page.mouse.click(10, 10);
  await page.waitForTimeout(200);

  // 최적 탐색 OFF
  await page.getByRole('button', { name: /최적 탐색/ }).click();
  await page.waitForTimeout(200);

  // 개인 전기자전거
  await page.getByRole('button', { name: /전기자전거/ }).click();
  await page.waitForTimeout(200);

  // 시간 우선
  await page.getByRole('button', { name: /시간 우선/ }).click();
  await page.waitForTimeout(200);

  // 날씨 선택 (이모지 포함 완전 일치로 "공유킥보드 (준비중)" 등 다른 버튼과 구분)
  if (weatherKey) {
    console.log(`  날씨 선택: ${weatherLabel}`);
    const emojiMap = { RAIN: '🌧', SNOW: '❄️', HEAT: '☀️', COLD: '🥶' };
    const emoji = emojiMap[weatherKey];
    await page.getByRole('button', { name: `${emoji} ${weatherLabel}` }).click();
    await page.waitForTimeout(200);
  }

  await page.getByRole('button', { name: '경로 탐색' }).click();
  await page.waitForURL(/\/routes/, { timeout: 10000 });

  try {
    await page.waitForFunction(
      () => {
        const text = document.body.innerText;
        return /\d+분/.test(text) && (text.includes('CO') || text.includes('도보'));
      },
      { timeout: 60000 }
    );
  } catch (e) {
    console.log(`  (타임아웃: ${e.message})`);
  }
  await page.waitForTimeout(3000);

  console.log(`  캡처: output/playwright/${outputName}`);
  await page.screenshot({
    path: resolve(OUT_DIR, outputName),
    fullPage: true,
  });

  await browser.close();
}

// CLEAR 기준
await captureScenario('', '기본', 'routes-clear.png');

// RAIN 시나리오
await captureScenario('RAIN', '비', 'routes-rain.png');

console.log('\n✅ CLEAR / RAIN 시나리오 캡처 완료');
