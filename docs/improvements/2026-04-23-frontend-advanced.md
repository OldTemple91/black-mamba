# 프론트엔드 고도화 — Timeline Bar + Dark Mode + Skeleton

> 작업일: 2026-04-23
> 담당 Phase: (로드맵 외) 프로젝트 시각 완성도
> 공수: 실측 약 2시간
> 커밋: TBD

---

## 1. 배경 (Why)

직전 리뉴얼(Split Layout + Glassmorphism)로 **구조** 는 잡혔지만, **Citymapper / Google Maps 수준의 시각적 세련도** 는 부족했다. 특히:

1. **"이동수단 체인" 이 텍스트 나열** — "🚇 2호선 → 🚶 도보 → 🚲 자전거" 한 줄로만. 각 구간이 얼마나 오래 걸리는지 시각 직감 불가.
2. **다크 모드 미지원** — 2026 UI/UX 트렌드 필수 항목.
3. **로딩이 "경로를 탐색 중입니다…" 텍스트 한 줄** — 레이아웃 shift + 재미없음.
4. **에러 상태가 plain 카드** — 복구 경로 약함.

## 2. 구현 (What)

### 2-1. Route Leg Timeline Bar — Citymapper 스타일

`RouteTimelineBar.jsx` — 전체 소요시간을 각 leg 비율대로 분할한 **수평 막대 그래프**.

```
[🚶 2' | 🚇 2호선 20' ━━━━━━━━━━━━ | 🚲 6' ]
                                         총 28분
```

핵심 설계:

- **서울 지하철 공식 색상** 15개 노선 하드코딩 (`SEOUL_SUBWAY_COLORS`)
  - 2호선 `#00A84D`, 9호선 `#BDB092`, 신분당선 `#D4003B` 등
- **Leg 타입별 색상 폴백**: WALK=slate, BIKE=emerald, KICKBOARD=violet
- **ODsay lineColor 우선 적용** 후 없으면 위 표에서 조회
- **너비 12% 미만은 라벨 숨김** — 텍스트 넘침 방지
- **hover 시 밝기 증가** — 미세 인터랙션
- **총 소요시간 요약** — "총 28분 · 3개 구간"

### 2-2. Dark Mode — 전면 지원

**Tailwind v4 class 전략**:
```css
@import "tailwindcss";
@custom-variant dark (&:where(.dark, .dark *));
```

**useDarkMode 훅**:
1. localStorage `bm:theme` 확인
2. OS 선호도 (`prefers-color-scheme`) 폴백
3. 기본 light
4. `document.documentElement` 에 `.dark` 토글

**ThemeToggle 컴포넌트**:
- 🌙 / ☀️ 아이콘 토글
- hover 시 살짝 회전 (+12deg)
- 다크 모드에선 호박색 shadow glow

**글래스 패널 다크 대응**:
```css
.dark .glass-panel {
  background: rgba(30, 41, 59, 0.62);
  border-color: rgba(51, 65, 85, 0.6);
}
```

**body gradient mesh 다크**:
```css
.dark body {
  background-color: #0b1220;
  background-image:
    radial-gradient(..., rgba(59, 130, 246, 0.18) ...),
    radial-gradient(..., rgba(139, 92, 246, 0.14) ...),
    radial-gradient(..., rgba(6, 182, 212, 0.10) ...);
}
```

다크에서 그라디언트를 **더 진하게** 올려 네온 포인트로 깊이감 추가.

### 2-3. Skeleton Loading Cards

`RouteCardSkeleton.jsx` — 실제 `RouteCard` 구조를 그대로 흉내내어 **레이아웃 shift 제거**.

- 헤더(배지+시간/비용) / Timeline bar / 메타 배지 / Comparison bars 각 영역
- `animate-pulse` 로 회색 부드러운 깜박임
- 다크 모드 변경 시 `bg-slate-700` 자동 전환

`RouteListPage` 로딩 시:
```
[🔵 🐍 경로 탐색 중 · 서초 아파트 → 성수 카페거리]
[Skeleton 1] [Skeleton 2] [Skeleton 3] [Skeleton 4]
```

이전 "경로를 탐색 중입니다…" 텍스트 한 줄 대비, 실제 콘텐츠가 들어올 레이아웃이 그대로 미리 보여 **perceived performance** 향상.

### 2-4. Error State 리뉴얼

그라디언트 아이콘 박스 + 명확한 2개 CTA:

```
[⚠️ 그라디언트 박스]
검색을 계속할 수 없습니다
<에러 메시지>
출발지나 목적지를 조금 더 넓게 잡아주세요.

[🏠 다시 검색 (gradient)] [← 이전]
```

---

## 3. 검증 & 성과 (Result)

### 3-1. 스크린샷 6종 생성

- `main-page.png` — 라이트, Timeline bar + split layout
- `routes-page.png` — 라이트, 5개 경로 카드에 timeline bar 전부
- `routes-clear.png` / `routes-rain.png` — 라이트, weather 비교
- **`main-page-dark.png`** — 다크, 글래스 + 그라디언트 mesh
- **`routes-page-dark.png`** — 다크, timeline bar 색상 유지

### 3-2. 빌드 사이즈

```
Before: CSS 36.69 kB / gzip  7.18 kB
After:  CSS 45.23 kB / gzip  8.28 kB
Δ     : +8.5 kB raw / +1.1 kB gzip
```

Timeline + Dark variants + Skeleton 모두 포함해 **+1.1 kB gzip**. 무시 가능.

### 3-3. 색상 시스템 정확도

실측 검증:
- 2호선 leg → `#00A84D` 초록 ✓
- 9호선 leg → `#BDB092` 금색 ✓
- 신분당선 → `#D4003B` 레드 ✓
- ODsay 에서 lineColor 오면 그대로, 없으면 위 표 조회 → 한 번도 기본 fallback 안 탐

### 3-4. Dark Mode UX

- **초기 로드**: localStorage 우선 → OS 선호도 → light
- **토글 전환**: 배경·텍스트·보더 색 0.3s 부드러운 전환
- **재방문**: localStorage 유지로 깜박임 없음

---

## 4. 사이드 이펙트 & 한계

### 4-1. 카드 내부 일부 섹션 다크 미완
Hub summary / Generation diagnostics / Fallback diagnostics 에 `bg-violet-50` / `bg-amber-50` 같은 라이트 전용 색이 남아 다크에서 blocky.
→ 핵심 UX(헤더/카드 외곽/timeline/메타)는 모두 다크 적용됨. 2차 보강에서 완결.

### 4-2. 지하철 라인 색상 하드코딩
서울 15개 라인만 지원. 경기·인천 전용 라인 확장 시 추가 테이블 필요.
→ `SEOUL_SUBWAY_COLORS` 를 상수 파일로 분리하면 쉽게 확장 가능.

### 4-3. prefers-reduced-motion 미반영
`animate-pulse`, `animate-gentle-pulse` 가 설정 사용자에게 거슬릴 수 있음.
→ 차기 차수에서 `@media (prefers-reduced-motion: reduce)` 규칙 추가.

### 4-4. Skeleton 카드는 경로 수를 추정 못함
4개로 하드코딩. 실제 결과가 1개뿐이면 약간 과함. 큰 실무 문제는 아님.

---

## 5. 기록

> "Split layout 리뉴얼까지 하고 나서 '구조는 잡혔는데 아직 Citymapper 수준은 아니네' 라는 느낌이 들었습니다. 세 가지 더 얹었습니다.
>
> 하나 — **Route Timeline Bar**. 경로 전체 소요 시간을 각 구간 비율대로 수평 막대로 나눠 그립니다. 서울 지하철 15개 노선 공식 색상을 하드코딩해서 '2호선 20분' 구간은 정확히 초록색으로, '신분당선 5분' 은 빨간색으로 렌더됩니다. Google Maps 와 Citymapper 가 쓰는 패턴이죠.
>
> 둘 — **Dark Mode**. Tailwind v4 의 `@custom-variant dark (&:where(.dark, .dark *))` 전략에 커스텀 훅으로 localStorage + prefers-color-scheme 우선순위를 엮었습니다. 글래스 패널도 다크 전용 (`rgba(30,41,59,0.62)`) 으로 자동 전환되고, 그라디언트 mesh 는 다크에서 채도를 더 올려 네온 포인트로 씁니다.
>
> 셋 — **Skeleton Loading**. '경로 탐색 중입니다…' 한 줄 텍스트 대신 실제 카드 구조를 그대로 흉내낸 skeleton 4개를 보여줘 perceived performance 를 올립니다. 레이아웃 shift 가 0 이에요.
>
> 결과적으로 gzip +1.1 kB 로 이 모든 변화를 넣었습니다."

---

## 6. 관련 문서
- [프론트엔드 UI/UX 리뉴얼 (split layout)](./2026-04-23-frontend-polish.md) — 직전 1차 리뉴얼
- `frontend/src/components/route/RouteTimelineBar.jsx` — 새 컴포넌트
- `frontend/src/components/route/RouteCardSkeleton.jsx` — 스켈레톤
- `frontend/src/components/common/ThemeToggle.jsx` — 다크 토글
- `frontend/src/hooks/useDarkMode.js` — 다크 모드 훅
- `scripts/screenshots/capture-dark-mode.mjs` — 다크 캡처 자동화
