/**
 * README 용 스크린샷 자동 캡처 스크립트.
 *
 * 전제조건:
 *   - docker compose up -d (Qdrant/Grafana/Prometheus/Loki/Tempo)
 *   - 앱 기동 (bootRun, port 8081)
 *   - Qdrant 에 시드 데이터 있음 (POST /api/rag/admin/seed 수행)
 *   - 메트릭 쌓이게 /api/routes 몇 번 호출
 *
 * 실행:
 *   node scripts/screenshots/capture.mjs
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = resolve(__dirname, '../../docs/images');
mkdirSync(OUT_DIR, { recursive: true });

// Grafana 기본: docker-compose 의 GF_AUTH_ANONYMOUS_ENABLED=true 설정으로 로그인 불필요
const shots = [
  {
    name: '01-grafana-overview.png',
    title: 'Grafana — Overview 대시보드 (3축 관측성)',
    url: 'http://localhost:3000/d/black-mamba-overview?orgId=1&refresh=5s&kiosk=tv',
    viewport: { width: 1680, height: 980 },
    wait: 4000,
  },
  {
    name: '02-grafana-route-performance.png',
    title: 'Grafana — Route Performance 대시보드',
    url: 'http://localhost:3000/d/black-mamba-route-performance?orgId=1&refresh=5s&kiosk=tv',
    viewport: { width: 1680, height: 980 },
    wait: 4000,
  },
  {
    name: '03-grafana-external-apis.png',
    title: 'Grafana — External APIs 대시보드',
    url: 'http://localhost:3000/d/black-mamba-external-apis?orgId=1&refresh=5s&kiosk=tv',
    viewport: { width: 1680, height: 980 },
    wait: 4000,
  },
  {
    name: '04-qdrant-dashboard.png',
    title: 'Qdrant 대시보드 — navigation-route-history (200+ points)',
    url: 'http://localhost:6333/dashboard#/collections/navigation-route-history',
    viewport: { width: 1600, height: 900 },
    wait: 3000,
  },
  {
    name: '05-swagger-ui.png',
    title: 'Swagger UI — 전체 엔드포인트 (RAG / Stream / Admin)',
    url: 'http://localhost:8081/swagger-ui/index.html',
    viewport: { width: 1480, height: 1400 },
    wait: 3000,
    fullPage: true,
  },
];

const browser = await chromium.launch();
const context = await browser.newContext({ locale: 'ko-KR' });

for (const shot of shots) {
  const page = await context.newPage();
  await page.setViewportSize(shot.viewport);
  console.log(`[CAPTURE] ${shot.name} ← ${shot.url}`);
  try {
    await page.goto(shot.url, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(shot.wait);
    const outPath = resolve(OUT_DIR, shot.name);
    await page.screenshot({
      path: outPath,
      fullPage: !!shot.fullPage,
    });
    console.log(`  ✓ saved: ${outPath}`);
  } catch (e) {
    console.error(`  ✗ failed: ${e.message}`);
  }
  await page.close();
}

await browser.close();
console.log('\nAll screenshots done.');
