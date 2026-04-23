# 프론트엔드 전수 감사 — 지도 버그 + 다크 모드 누락 + Sticky 높이

> 작업일: 2026-04-23
> 담당 Phase: (로드맵 외) 품질 감사
> 공수: 실측 약 1.5시간
> 커밋: TBD

---

## 1. 배경 (Why)

사용자가 **"교대에서 양재역 가는 선이 일직선으로 되있는데 맞아?"** 라는 한 마디로 포트폴리오 곳곳에 잠재된 **미흡한 부분들** 을 전수 점검해야 한다는 문제를 제기했다.

이전 리뉴얼 4회로 Split Layout, Glassmorphism, Dark Mode, Route Timeline Bar 까지 쌓았지만, **중간 컴포넌트들은 다크 variants 가 놓여있는 채로 방치**되어 있었고, **지도는 근본적 버그 2개**가 묻혀 있었다.

---

## 2. 발견된 문제들

### 2-1. 지도 렌더링 — 3개 이슈

| # | 문제 | 영향 |
|---|------|-----|
| 1 | **TRANSIT leg 이 start↔end 일직선** | 백엔드가 passThroughStations 를 제공해도 프론트가 무시. 교대→양재 같은 구간이 허공에 직선으로 보임. |
| 2 | **지도 컨테이너 `h-96` 하드코딩** | MainPage / RouteListPage 가 `lg:h-[calc(100vh-Xrem)]` sticky 로 감싸도 내부 div 는 384px 고정 → sticky 확장 실패. |
| 3 | **fitBounds 없음** | 첫 출발지로만 setCenter. 목적지가 viewport 밖으로 나가는 경우 다수. |

### 2-2. 다크 모드 누락 — 5개 파일

| 파일 | 누락 영역 |
|------|---------|
| `NaverMap.jsx` | Legend 박스 + 맵 외곽 ring |
| `MobilitySelector.jsx` | mobility 버튼 5개 + "Recommendation Preference" 패널 전체 |
| `RouteListPage.jsx` | "Selected Route" / "Preference Compare" 상단 두 패널 + 비교 세그먼트 |
| `LegItem.jsx` | 상세보기 펼침 영역 전체 (타임라인 dot, 연결선, 정류장 리스트, 태그) |
| `MainPage.jsx` | 자동완성 드롭다운 목록 |

이전 다크 모드 도입 시 RouteCard 본체만 손보고 나머지는 놓친 것.

---

## 3. 구현 (What)

### 3-1. 지도 버그 수정 — 3단계 폴리라인 우선순위

```js
// Before: routeCoordinates 없으면 무조건 [start, end] 직선
// After: 3단계 폴백
if (leg.routeCoordinates?.length > 1) {
  coords = leg.routeCoordinates            // 1. Tmap 도로 좌표 (도보/이동수단)
} else if (leg.type === 'TRANSIT'
           && leg.transitInfo?.passThroughStations?.length > 0) {
  coords = [leg.start, ...leg.transitInfo.passThroughStations, leg.end]  // 2. 지하철·버스 경유 정류장 (NEW)
} else {
  coords = [leg.start, leg.end]            // 3. 폴백 직선
}
```

### 3-2. 지도 sticky 높이 수정

```jsx
// Before
<div id="naver-map" className="w-full h-96 ..." />

// After
<div className="relative h-full w-full">
  <div id="naver-map" className="h-full w-full min-h-[360px] ..." />
</div>
```

부모 sticky 컨테이너의 `h-[calc(100vh-5rem)]` 이 이제 지도에 정상 전달됨.

### 3-3. fitBounds 도입

```js
// 경로 전체가 화면에 들어오도록
const allCoords = []
selectedRoute.legs.forEach(leg => {
  if (leg.start) allCoords.push(leg.start)
  if (leg.end) allCoords.push(leg.end)
  if (leg.routeCoordinates) allCoords.push(...leg.routeCoordinates)
  if (leg.type === 'TRANSIT' && leg.transitInfo?.passThroughStations) {
    allCoords.push(...leg.transitInfo.passThroughStations)
  }
})
const bounds = new window.naver.maps.LatLngBounds(...)
allCoords.forEach(c => bounds.extend(new window.naver.maps.LatLng(c.lat, c.lng)))
mapRef.current.fitBounds(bounds, { top: 60, right: 40, bottom: 60, left: 40 })
```

### 3-4. 다크 모드 variants 전수 추가

5개 파일 전체를 훑어 `bg-white`, `border-slate-200`, `text-slate-400` 등에 해당 `dark:` 를 쌍으로 추가. 특별히 세심하게 처리한 것들:

- **비교 세그먼트 색상** (시간 빠름/느림, 도보 적음/많음, 환승 적음/많음) — emerald/amber 3단 모두 다크 대응
- **"Recommendation Preference" 선택 카드** — 선택 시 배경 명도가 반전되는 구조 (`bg-slate-900 text-white` ↔ `bg-slate-200 text-slate-900`)
- **LegItem 노선 배지** — ODsay lineColor 원본 유지하고 텍스트 태그만 `dark:brightness-125`
- **Selected Route 패널** — sky/amber 배지가 다크에서 흐려지지 않도록 `/40` 투명도

---

## 4. 검증 & 성과 (Result)

### 4-1. 지도 Before / After

| 구분 | Before | After |
|------|--------|-------|
| 지도 크기 | 384px 정사각형 고정 | sticky 영역 전체 높이 (viewport-80px) |
| TRANSIT 선 모양 | 일직선 | 경유 정류장 경유 곡선 |
| 화면 밖 경로 | 자주 발생 | fitBounds 로 자동 조정 |
| Legend | 라이트 전용 | 다크 대응 |

### 4-2. 다크 모드 Before / After

이전엔 결과 페이지 다크 모드에서 **"Selected Route" / "Preference Compare" 두 패널이 흰 배경으로 튀어나와** 전체 톤을 깨뜨리고, 카드 상세 펼침 시 **LegItem 영역이 밝게 반전** 되었다. 이제는 전 영역이 일관된 다크 팔레트.

### 4-3. 스크린샷 7종 재생성 + 데모 GIF 재녹화

- `main-page.png` / `main-page-dark.png`
- `routes-page.png` / `routes-page-dark.png`
- `routes-clear.png` / `routes-rain.png`
- `mobile-*` 4장
- **`demo.gif` 2.1 MB 재녹화** (지도 크기 확장 + 경로 곡선화 반영)

### 4-4. 빌드 영향

```
Before (4차 polish): CSS 8.66 kB gzip
After  (감사 완료):   CSS 8.82 kB gzip
Δ     : +0.16 kB gzip
```

이 모든 수정을 0.16 kB gzip 추가로 처리.

---

## 5. 사이드 이펙트 & 한계

### 5-1. ODsay 가 passStopList 를 안 주는 leg 은 여전히 직선
대부분의 버스 구간이 해당. 이건 외부 API 한계라 근본 해결은 별도 지도 데이터 통합 필요.

### 5-2. 모바일 지도 크기 고정
모바일에선 `h-[400px]` 로 sticky 효과 없이 고정. 데스크톱 전용 sticky. 모바일 반응형 sticky 는 별도 UX 설계 필요.

### 5-3. fitBounds 가 너무 공격적일 수 있음
OD 가 가까우면 40px padding 으로 확대가 과할 수 있음. 추후 `maxZoom` 제한 추가 가능.

### 5-4. LegItem 노선 배지 (`backgroundColor: lineColor` 인라인) 다크 대응
인라인 스타일은 CSS variant 로 바꿀 수 없어 `dark:brightness-125` 로 간접 조정. 2호선 초록/9호선 금색 등 원색 유지는 의도적.

---

## 6. 발표 스토리

> "다크 모드 도입 시엔 RouteCard 본체만 손보고 주변 컴포넌트들 (MobilitySelector, LegItem, NaverMap Legend, RouteListPage 상단 패널들) 을 놓친 게 있었습니다. 사용자 피드백 한 줄 — **'교대에서 양재역이 일직선으로 보이는데?'** — 으로 전수 점검을 시작했고, 사실 그 스크린샷 하나에 **지도 버그 3개 + 다크 누락 5개 파일** 이 다 들어있었습니다.
>
> 지도는 세 가지 — (1) TRANSIT 이 직선으로 그려짐 (passThroughStations 미사용), (2) 컨테이너 h-96 하드코딩 (sticky 무효화), (3) fitBounds 없음 (화면 밖 경로) — 를 한 번에 수정. 폴리라인은 **Tmap 도로 좌표 → 경유 정류장 → 직선 폴백** 3단 우선순위.
>
> 다크는 5개 파일에 **dark: variants 를 쌍으로 추가**. 특히 emerald/amber/sky 배지는 `/40` 투명도로 다크에서 자연스럽게 내려앉게.
>
> 시각적 충격은 컸지만 gzip 은 0.16 kB 만 추가되는 변경이었습니다."

---

## 7. 관련 문서
- `frontend/src/components/map/NaverMap.jsx` — 3단 폴리라인 + fitBounds + sticky 높이
- `frontend/src/components/route/LegItem.jsx` — 상세 영역 다크 전수 적용
- `frontend/src/components/search/MobilitySelector.jsx` — 버튼 + 선호도 패널 다크
- `frontend/src/pages/RouteListPage.jsx` — 상단 두 패널 다크
- `frontend/src/pages/MainPage.jsx` — 자동완성 드롭다운 다크
- [프론트 4차 polish](./2026-04-23-frontend-polish.md) — 직전 작업에서 놓친 영역 회수
