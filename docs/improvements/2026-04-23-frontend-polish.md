# 프론트엔드 UI/UX 리뉴얼 (2026 트렌드 반영)

> 작업일: 2026-04-23
> 담당 Phase: (로드맵 외) 프로젝트 완성도
> 공수: 실측 약 2시간
> 커밋: TBD

---

## 1. 배경 (Why)

오늘 추가한 4축 관측성 / Carbon / Weather / ADR / 알고리즘 카탈로그가 **백엔드·문서** 에 모두 반영됐지만, 프론트엔드는 여전히 **모바일 가정의 320px 단일 컬럼** 상태.

**1280px 데스크톱에서 좌상단 320px 에만 콘텐츠가 몰리고, 나머지 ~950px 가 빈 여백.**

### 구체적 문제

| # | 문제 | 근거 |
|---|------|------|
| 1 | **좌상단 편중** — 데스크톱 레이아웃 부재 | 직전 스크린샷에서 우측 900px+ 가 빈 여백 |
| 2 | **Vite boilerplate 잔존** | `App.css` 에 `logo-spin`, `read-the-docs` 등 React 템플릿 흔적 |
| 3 | **추천 경로 ≠ 일반 경로 시각 차이 없음** | 모두 같은 `border-gray-200 bg-white` |
| 4 | **Carbon · Risk · Comparison 배지 뒤섞임** | 메타 라인이 카드당 3~4줄로 시각 혼잡 |
| 5 | **Primary button 이 연한 파랑** | 핵심 CTA 가 부각되지 않음 |
| 6 | **브랜드 아이덴티티 약함** | 헤더 "🐍 Black Mamba" 만 — tagline / MaaS 메시지 부재 |
| 7 | **OD 요약 noscroll** | RouteListPage 에서 스크롤 하면 어디서 어디로 가는지 기억 안 남 |

### 2026 트렌드 참고

- **Bottom sheet / Split layout** — 지도는 크게, 컨트롤은 한쪽으로 (Apple Maps, Citymapper)
- **Glassmorphism** — `backdrop-blur` + 반투명 배경으로 depth
- **Visual hierarchy** — primary action gradient + shadow, 추천 카드 subtle glow
- **Whitespace 의도적 사용** — 덜어낼수록 명확
- **Sticky 네비게이션** — 스크롤 중에도 컨텍스트 유지

References:
- [Top UI/UX Design Trends 2026 (AND Academy)](https://www.andacademy.com/resources/blog/ui-ux-design/latest-ui-ux-design-trends/)
- [Transportation App UI/UX Best Practices (Fuselab)](https://fuselabcreative.com/transportation-app-ui-ux-design-best-practices/)
- [Glassmorphism with Tailwind CSS (FlyonUI)](https://flyonui.com/blog/glassmorphism-with-tailwind-css/)

---

## 2. 구현 (What)

### 2-1. 변경 파일 (6개)

```
frontend/src/index.css                              # body gradient mesh + 폰트 + 스크롤바
frontend/src/App.css                                # Vite boilerplate 제거, 유틸 애니메이션
frontend/src/pages/MainPage.jsx                     # split layout + Hero + glass panel
frontend/src/pages/RouteListPage.jsx                # sticky OD header + split layout
frontend/src/components/route/RouteCard.jsx         # 시각 위계 + 메타 라인 통합
scripts/screenshots/capture-frontend.mjs            # placeholder 정규식 업데이트
```

### 2-2. Phase 1 — 디자인 시스템

`index.css`:
- body 에 **3중 radial-gradient mesh** (파랑 → 보라 → 청록) 을 저채도로 깔아 MaaS 의 '이동감'
- 시스템 폰트 스택 (Pretendard → Apple SD → system)
- 얇은 커스텀 스크롤바

`App.css`:
- Vite 보일러플레이트 완전 제거
- `animate-fade-in-up` 카드 등장 애니메이션
- `animate-gentle-pulse` primary button 호흡 효과
- `.glass-panel` 클래스 (backdrop-blur 20px + saturate 180%)
- `.recommended-ring` 그라디언트 보더 (CSS mask 로 구현)

### 2-3. Phase 2 — MainPage Split Layout

```
lg (≥1024px):
  ┌────────────────┬──────────────────────────────┐
  │ Hero           │                              │
  │ 🐍 Black Mamba │                              │
  │ + tagline      │                              │
  │                │     지도 (full-height        │
  │ 검색 패널      │      sticky)                 │
  │  (glass)       │                              │
  │  - 출발지      │                              │
  │  - ⇅ swap      │                              │
  │  - 목적지      │                              │
  │  - Mobility    │                              │
  │  - Weather     │                              │
  │  - 🚀 검색 CTA │                              │
  │                │                              │
  │ [4축][8종][18건]│                              │
  └────────────────┴──────────────────────────────┘
  sticky                                 sticky

모바일: vertical stack (Hero → 검색 → 지도)
```

핵심 컴포넌트 변경:

- **Hero 영역** — 11×11 그라디언트 로고 박스 + "MaaS Routing Engine" tagline + 3줄 value prop
- **출발/목적지 입력** — 🟢 초록·🔴 빨강 핀 시각 차별화 + focus ring + swap(⇅) 버튼
- **Weather 옵션** — 5개 버튼, 선택 시 gradient + hint 문장 자동 표시
- **Primary CTA** — 청→보라 그라디언트 + `animate-gentle-pulse` + 비활성 시 명확한 비활성화 메시지
- **하단 스탯** — "4축 / 8종 / 18건" 으로 프로젝트 스케일 한눈 파악

### 2-4. Phase 3 — RouteCard 시각 위계

**Before:** 모든 카드가 동일한 스타일, 메타 정보가 3~4줄로 흩어짐.

**After:**
- **추천 카드 = `recommended-ring`** (블루-퍼플 그라디언트 보더)
- **선택된 카드 = ring-2 shadow-blue** (상호 배타적)
- **메타 라인 통합** — 🔥 단축 / 🌱 CO₂ / 🌿 친환경 / 자가용 감축이 **한 줄에** 정렬
- **시간 · 비용 tabular-nums** 으로 정렬 안정화
- 카드 등장 시 `animate-fade-in-up`

### 2-5. Phase 4 — RouteListPage Split Layout

```
sticky top:  ← [OD 요약 · 🌧 비] · 🔧 디버그 토글

lg (≥1024px):
  ┌─────────────────────┬────────────────────┐
  │ [카드 1] ⭐ 추천    │                    │
  │ [카드 2]            │                    │
  │ [카드 3]            │   지도 (선택 경로) │
  │ [카드 4]            │   sticky           │
  │ [카드 5]            │                    │
  │ ...                 │                    │
  └─────────────────────┴────────────────────┘
```

- 스크롤 중에도 OD 가 sticky 헤더로 유지
- Weather 설정 시 상단에 `🌧 비` 같은 배지로 현 컨텍스트 가시화
- 지도는 선택된 경로를 항상 우측에 함께 보여줌 → 카드 클릭으로 즉시 시각 피드백

---

## 3. 검증 & 성과 (Result)

### 3-1. 스크린샷 재생성 (4종)

- `main-page.png` — 데스크톱 split 확인 (좌 검색 패널 + 우 full-height 지도)
- `routes-page.png` — 5개 경로 카드 중 1등에 gradient ring glow
- `routes-clear.png` — CLEAR 기준
- `routes-rain.png` — 상단에 "🌧 비" 배지 + 순위 변화 (TRANSIT_WITH_BIKE → TRANSIT_ONLY 2등 진입)

### 3-2. 빌드 결과

```
vite v7.3.1 building client environment for production...
✓ 99 modules transformed.
dist/assets/index-CP17pUb8.css   36.69 kB │ gzip:   7.18 kB
dist/assets/index-D1eMrwrx.js   321.74 kB │ gzip: 104.41 kB
✓ built in 810ms
```

CSS 만 23 kB → 37 kB (+60%). 추가 14 kB 가 split layout + glass + 애니메이션의 비용. gzip 기준 2 kB 미만 증가 → **실서비스 영향 무시 가능**.

### 3-3. Before/After 비교

| 영역 | Before | After |
|------|--------|-------|
| 데스크톱 활용 | 320px / 1280px = **25%** | 상단 최대폭 (7xl ≈ 1280px) = **100%** |
| 브랜드 인식 | 로고만 | 로고 + tagline + MaaS 메시지 |
| Primary CTA 시인성 | 연한 파랑 | 청→보라 그라디언트 + pulse |
| 추천 카드 차별화 | 없음 | gradient ring glow |
| OD 컨텍스트 유지 | 스크롤하면 사라짐 | sticky 헤더 + 날씨 배지 |
| 카드 메타 혼잡 | 3~4줄 | 통합 1줄 |
| 스크롤 경험 | 지도 하단, 매번 스크롤 | 지도 우측 sticky |

---

## 4. 사이드 이펙트 & 한계

### 4-1. 모바일 레이아웃 특별 최적화는 안 함
`lg:` 미디어쿼리 기반으로 모바일은 단순 vertical stack. 바텀 시트 같은 **native 모바일 UX** 는 별도 작업 필요.

### 4-2. 다크 모드 미지원
2026 트렌드 상 당연하지만 이번 범위 밖. 다음 단계로 `dark:` variants 추가 예정.

### 4-3. 접근성(a11y) 정밀 검증 안 함
- 그라디언트 → 대비비 충분성 미검증 (WCAG AA)
- 스크린 리더 라벨 미추가
- 키보드 네비게이션 `tabindex` 지정 최소화

### 4-4. 애니메이션 `prefers-reduced-motion` 미반영
`animate-gentle-pulse` 가 설정 사용자에게 거슬릴 수 있음. 다음 차수에서 반영.

### 4-5. Pretendard 폰트는 CDN 미포함
로컬 설치 없으면 fallback (Apple SD → system) 으로 떨어짐. 대다수 한국 사용자는 OS 에 있어 문제 없지만, 완벽한 통일성 원하면 CDN 추가 필요.

---

## 5. 기록

> "백엔드·문서가 Tier 1·2 로 두꺼워졌는데, **프론트가 여전히 모바일 320px 가정** 이라 1280px 모니터에선 좌상단 25% 영역에만 콘텐츠가 몰려있었습니다.
>
> 2026 UI/UX 트렌드 - **Split layout, Glassmorphism, Visual hierarchy, Sticky 네비게이션** - 을 참고해 리뉴얼했습니다.
>
> 핵심은 **CSS 한 장으로 생긴 변화가 아니라 정보 설계 자체가 바뀌었다** 는 점입니다. 추천 카드가 gradient ring 으로 즉시 구분되고, OD 가 sticky 헤더로 컨텍스트 유지, 지도가 우측에 항상 붙어있어 카드 클릭만으로 시각 피드백 오고, primary CTA 가 호흡하듯 pulse 하면서도 비활성 시 명확한 메시지로 전환됩니다.
>
> gzip 기준 2 kB 증가로 이 모든 변화를 만들었고, Playwright 스크린샷 4종으로 전/후 비교도 자동화했습니다."

---

## 6. 관련 문서
- [C-2 Carbon Footprint](./2026-04-23-C2-carbon-footprint.md) — carbon 배지가 노출되는 위치
- [A-4 Weather-aware Routing](./2026-04-23-A4-weather-aware-routing.md) — 날씨 배지 sticky 표시
- [운영 마감 Polish Pack](./2026-04-23-polish-pack.md) — 직전 프론트 렌더링 첫 작업
- `frontend/src/App.css`, `index.css` — 디자인 시스템 토큰
- `scripts/screenshots/capture-frontend.mjs` — 자동 캡처 스크립트
