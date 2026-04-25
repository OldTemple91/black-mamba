# Tier S 통신력 — Mermaid 다이어그램 + 모바일 뷰 + 데모 GIF

> 작업일: 2026-04-23
> 담당 Phase: (로드맵 외) 프로젝트 통신력
> 공수: 실측 약 2시간
> 커밋: TBD

---

## 1. 배경 (Why)

오늘 10 커밋까지 와서 코드/문서는 충분히 두꺼워졌지만, **프로젝트 통신력** 관점에서 치명적 구멍 3개가 남아있었다.

| 구멍 | 왜 치명적인가 |
|------|-------------|
| 🚫 아키텍처 다이어그램 부재 | 알고리즘 카탈로그는 있지만 시스템 전체 그림이 없어 "3초 파악" 불가 |
| 🚫 모바일 스크린샷 부재 | `lg:` 미디어쿼리로 만들었지만 실제 모바일 뷰 증거가 없음 |
| 🚫 데모 영상/GIF 부재 | 정적 스크린샷만 — 실제 동작감 / 상호작용감 전달 안 됨 |

**"새 기여자가 README 스크롤 3초 안에 확신을 가지게 하는 요소"** 들이다. 코드 더 얹기보다 이게 우선.

---

## 2. 구현 (What)

### 2-1. Mermaid 아키텍처 다이어그램 (2종)

**시스템 다이어그램** — 6개 레이어:
- Client (React 19 + Vite + Tailwind v4)
- API Layer (4개 컨트롤러)
- Application Layer (Orchestration + 5패턴 + 후처리)
- Infra Layer (4개 외부 API 어댑터 + Geohash Cache + Resilience4j)
- RAG Pipeline (Ollama + Qdrant)
- Observability 4축 (Prometheus/Loki/Tempo/Alertmanager + Grafana)

→ GitHub 가 Mermaid 자동 렌더링 → README 에 **인라인 시스템 그림** 이 뜸. 이미지 파일 0.

**모듈 의존 다이어그램** — Clean Architecture 증명:
- `api` → `application` → `domain`
- `infra` 가 Port 구현 (의존성 역전)
- domain / application 의 외부 의존성 0 을 시각적으로 증명

### 2-2. 모바일 뷰 스크린샷 (4종)

`scripts/screenshots/capture-mobile.mjs` 신설:
- Playwright `devices['iPhone 14']` viewport (390×844)
- 라이트/다크 각각 메인 + 결과 페이지
- localStorage `bm:theme` 초기 주입으로 테마 제어
- fullPage 스크린샷

결과 4장:
- `mobile-main-light.png`
- `mobile-main-dark.png`
- `mobile-routes-light.png`
- `mobile-routes-dark.png`

**반응형 검증** — 데스크톱 split 이 모바일에선 vertical stack 으로 자연 변환됨을 스크린샷으로 확정. 수정할 게 없었음 = 디자인이 제대로 반응형.

### 2-3. 데모 GIF (20초)

`scripts/screenshots/capture-demo-video.mjs` — Playwright `recordVideo` 로 webm 녹화:

시나리오 (12 단계):
1. 메인 페이지 Hero 애니메이션 등장
2. 라이트 → 다크 토글
3. 다크 → 라이트
4. 출발지 입력
5. 목적지 입력
6. 최적탐색 해제 + 전기자전거 + 시간 우선
7. 🌧 비 날씨 선택
8. 경로 탐색 클릭
9. Skeleton → 실제 카드 로드
10. 2번째 카드 클릭 (지도 연동)
11. 스크롤 다운
12. 결과 페이지 다크 토글

ffmpeg 변환 파이프라인:
```
webm (1.8 MB)
  → fps 8, scale 640×auto, palette 48색, bayer dither
  → GIF 1.85 MB (20초)
```

이전 시도 (fps 12, 800px, 96색) 은 4.3 MB 로 너무 무거움 — 3단계 튜닝 끝에 **1.85 MB** 로 안착. GitHub README 임베딩에 적절.

### 2-4. README 상단 재구성

최상단 순서:
1. 배지 + 한 줄 설명
2. **🎬 20초 데모 GIF** — 압도적 첫인상
3. ⚡ 한눈에 보기 테이블
4. **🏛 시스템 아키텍처 Mermaid** — 전체 그림
5. 📦 모듈 의존 다이어그램
6. 🧭 Orchestration 층 (자체 알고리즘 8종)
7. 🖥 Screenshots (라이트/다크 × 데스크톱/모바일 2×2×2 = 8장)

---

## 3. 검증 & 성과 (Result)

### 3-1. GIF 압축 3단계 튜닝 기록

| Step | 설정 | 크기 |
|------|------|-----|
| 1차 | fps 12, 800px, 96색 | 4.3 MB ❌ |
| 2차 | fps 10, 700px, 64색 | 2.6 MB ⚠️ |
| **3차** | **fps 8, 640px, 48색 + bayer dither** | **1.85 MB ✅** |

GitHub 는 10 MB 까지 허용하지만 **2 MB 이하** 가 로딩/CDN 친화적 임계.

### 3-2. Playwright 자동화 증거

스크린샷 자동화 스크립트 총 5종:
- `capture-frontend.mjs` — 데스크톱 라이트 2장
- `capture-dark-mode.mjs` — 데스크톱 다크 2장
- `capture-rain-scenario.mjs` — CLEAR vs RAIN 비교 2장
- **`capture-mobile.mjs`** — 모바일 4장 (신규)
- **`capture-demo-video.mjs`** — 20초 데모 비디오 (신규)

### 3-3. 모바일 반응형 검증 결과

수정 없이 통과한 이유 — 처음부터 모바일 기준으로 설계하고 `lg:` 미디어쿼리로 데스크톱을 "확장"한 구조. Tailwind 의 mobile-first 철학을 그대로 유지한 덕.

---

## 4. 사이드 이펙트 & 한계

### 4-1. GIF 가 여전히 1.85 MB
Loom / YouTube 링크로 대체하면 0 kB 이지만, README 에 **자동 재생 + 인라인** 되는 경험은 GIF 만 가능. 트레이드오프 감안 현상 유지.

### 4-2. Mermaid 가 다이어그램에 한정
시퀀스·상태·class diagram 도 되지만 이번엔 flowchart 2종만. 필요 시 확장.

### 4-3. 녹화 시 마우스 커서가 GIF 에 안 보임
Playwright `recordVideo` 는 커서를 기록하지 않음. 대신 클릭 시 hover/active 상태가 표시되므로 흐름은 이해 가능.

### 4-4. 모바일 viewport 는 iPhone 14 하나만 체크
Pixel / 갤럭시 / iPad 는 미검증. 필요 시 `devices['Pixel 7']` 등 추가 호출만 하면 됨.

---

## 5. 기록

> "프로젝트의 **통신력 구멍 3개** 를 한 번에 메웠습니다.
>
> **시스템 다이어그램** — Mermaid 로 GitHub 에 바로 렌더링되게 인라인 작성. 6 레이어 구조 (Client / API / Orchestration / Infra / RAG / Observability) 가 한눈에 보이게. 이미지 파일을 관리할 필요가 없어 다이어그램이 코드와 함께 Git 히스토리로 관리됩니다.
>
> **모바일 반응형** — Playwright `devices['iPhone 14']` viewport 로 실제 브라우저 엔진 기반 스크린샷 4장. 첫 설계부터 mobile-first 로 했기에 수정 없이 통과.
>
> **데모 GIF** — 20초에 12 단계 (테마 토글 → 입력 → 선택 → 탐색 → 결과 → 지도 연동 → 다크 전환) 를 담고 1.85 MB 로 압축. ffmpeg 파이프라인 (fps 8 + palette 48색 + bayer dither) 3단계 튜닝으로 4.3 MB → 1.85 MB.
>
> 이 세 가지가 **새 기여자가 README 를 3초 스크롤만 해도 확신을 가지게 만드는 요소** 입니다."

---

## 6. 관련 문서
- `scripts/screenshots/capture-mobile.mjs` — 모바일 자동화
- `scripts/screenshots/capture-demo-video.mjs` — 데모 비디오 녹화
- `output/playwright/demo.gif` — 최종 20초 데모
- [프론트 4차 polish](./2026-04-23-frontend-advanced.md) — 이 GIF 에 담긴 기능들
