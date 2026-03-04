# Black Mamba Navigation — 설계 문서

> "기존 네비게이션이 보여주지 않는 더 빠른 길"

**작성일:** 2026-03-04
**프로젝트:** black-mamba
**작성자:** sjw

---

## 1. 프로젝트 개요

### 핵심 아이디어
네이버/카카오 지도처럼 출발지-목적지를 입력하면 경로를 안내하되,
**대중교통 + 개인 이동수단(자전거, 킥보드)을 혼합한 최적 경로**를 제시하는 멀티모달 네비게이션 서비스.

### 차별화 포인트
기존 서비스는 `대중교통 경로` 또는 `자동차 경로` 만 제공.
Black Mamba는 **경로 자체를 재설계**하여 중간에 킥보드나 자전거를 활용하면
더 빠른 경우 이를 최우선 추천 경로로 제시한다.

**예시:**
```
기존: 버스5정거장(20분) → 도보(5분) → 버스3정거장(10분) → 도보(10분) = 45분
추천: 버스3정거장(18분) → 킥보드(9분) = 27분  ← 18분 단축!
```

### MVP 범위
- 대상 지역: 서울
- 이동수단: 대중교통, 따릉이(공유자전거), 공유킥보드, 개인 소유 이동수단
- 플랫폼: 웹 (React)

---

## 2. 아키텍처

### 전체 구조

```
┌─────────────────────────────────────────────────┐
│                   React (Frontend)               │
│  지도 렌더링 (네이버 지도)  +  경로 UI 표시        │
└────────────────────┬────────────────────────────┘
                     │ REST API
┌────────────────────▼────────────────────────────┐
│              Spring Boot Backend                 │
│                                                  │
│  ┌─────────────────────────────────────────┐    │
│  │         RouteOptimizationService         │    │
│  │  (핵심: 중간지점 탐색 + 경로 조합 생성)   │    │
│  └──────┬──────────┬──────────┬────────────┘    │
│         │          │          │                  │
│  ┌──────▼──┐ ┌─────▼────┐ ┌──▼──────────┐      │
│  │  ODsay  │ │  Naver   │ │  Mobility   │      │
│  │ Client  │ │  Client  │ │  Client     │      │
│  └──────┬──┘ └─────┬────┘ └──┬──────────┘      │
└─────────┼──────────┼─────────┼───────────────── ┘
          │          │         │
    ┌─────▼──┐  ┌────▼───┐ ┌──▼──────────────────────────┐
    │ ODsay  │  │ Naver  │ │ 서울 공공데이터 (따릉이)        │
    │  API   │  │  API   │ │ 국토부 TAGO API (공유킥보드)   │
    └────────┘  └────────┘ └─────────────────────────────┘
```

### 백엔드 모듈 구조 (4모듈)

```
black-mamba (루트 프로젝트)
├── api          → Controller, 설정, bootJar 생성
├── application  → 비즈니스 로직 (RouteOptimizationService 등)
├── domain       → 순수 도메인 모델 (Route, Leg, Location 등)
└── infra        → 외부 API 클라이언트 (ODsay, Naver, 따릉이, 국토부)
```

**의존성 방향:**
```
api → application → domain
api → infra → domain
```

---

## 3. 외부 API

| API | 용도 | 비고 |
|-----|------|------|
| ODsay API | 대중교통 경로 탐색 | 무료 플랜 존재 |
| 네이버 지도 API | 지도 렌더링, 자전거 경로 | 유료 |
| 서울 공공데이터 따릉이 API | 대여소 실시간 위치 + 잔여 대수 | 무료 |
| 국토부 TAGO API | 공유킥보드 실시간 위치 + 배터리 | 공공데이터포털, 무료 |

---

## 4. 핵심 알고리즘 — ODsay 멀티콜 + 중간지점 탐색

### 전체 흐름

```
[입력] 출발지 A, 목적지 B, 보유/사용 가능 이동수단

STEP 1. ODsay로 A→B 기본 대중교통 경로 획득
         → 경로 내 모든 정류장 목록 추출

STEP 2. 후보 환승 지점 필터링 (5~7개로 압축)
         → 전체 경로의 30~80% 지점 사이 정류장
         → 목적지까지 이동수단 이용 거리 범위 내
           (킥보드 5km / 자전거 10km 이내)
         → 따릉이: 반경 300m 내 대여소 존재 여부 확인

STEP 3. 각 후보 지점별 조합 시간 계산 (WebClient 병렬 호출)
         → ODsay: A → 후보지점 (대중교통 시간)
         → 네이버 API: 후보지점 → B (자전거/킥보드 시간)
         → 합산 + 이동수단 실시간 가용 여부 확인

STEP 4. 역방향 탐색 (이동수단 먼저)
         → 네이버 API: A → 인근 주요 환승역 (자전거/킥보드)
         → ODsay: 환승역 → B (대중교통)

STEP 5. 전체 후보 점수 계산 후 상위 3~5개 반환
```

### 후보 지점 필터링 기준

```
1. 전체 경로의 30~80% 지점 사이 정류장만
2. 목적지까지 직선거리:
     킥보드 → 5km 이내
     자전거 → 10km 이내
3. 따릉이 이용 시 → 반경 300m 내 대여소 + 잔여 대수 > 0
4. 킥보드 이용 시 → 반경 300m 내 국토부 TAGO API 기기 존재
```

### 스코어링

```
score = (총 이동시간   × 0.5)
      + (환승 횟수     × 0.2)
      + (비용          × 0.2)
      + (체력 부담     × 0.1)
```

### 외부 API 호출 횟수 (병렬 처리)

```
후보 지점 6개 기준:
  ODsay:  1(기본) + 6(후보→B) + 3(역방향) = 10회
  네이버: 6(후보→B) + 3(A→환승역)         = 9회
  따릉이: 1회 (근처 대여소 일괄 조회)
  국토부: 1회 (근처 킥보드 일괄 조회)
  → 총 21회 (Spring MVC + WebClient 병렬 처리, 목표 1~2초)
```

---

## 5. 도메인 모델

```java
// 전체 경로
Route {
    routeId: String
    type: RouteType        // TRANSIT_ONLY | TRANSIT_WITH_BIKE
                           // TRANSIT_WITH_KICKBOARD | BIKE_FIRST_TRANSIT
    totalMinutes: int
    totalCostWon: int
    score: double
    recommended: boolean
    legs: List<Leg>
    comparedToTransitOnly: Comparison
}

// 각 구간
Leg {
    type: LegType          // TRANSIT | WALK | BIKE | KICKBOARD
    mode: String           // BUS | SUBWAY | DDAREUNGI | PERSONAL_BIKE | KICKBOARD
    durationMinutes: int
    distanceMeters: int
    start: Location
    end: Location
    transitInfo: TransitInfo       // TRANSIT일 때
    mobilityInfo: MobilityInfo     // BIKE | KICKBOARD일 때
}

// 이동수단 정보
MobilityInfo {
    mobilityType: MobilityType     // PERSONAL | DDAREUNGI | KICKBOARD_SHARED
    operatorName: String           // 씽씽, 킥고잉, Lime 등
    deviceId: String
    batteryLevel: int              // 킥보드 배터리 %
    stationName: String            // 따릉이 대여소명
    lat: double
    lng: double
    availableCount: int            // 따릉이 잔여 대수
    distanceMeters: int            // 후보지점에서 거리
}

// 기존 대중교통 대비 비교
Comparison {
    originalMinutes: int
    savedMinutes: int
}
```

---

## 6. REST API 설계

### 핵심 엔드포인트

```
GET  /api/routes                         경로 탐색
GET  /api/routes/{routeId}               경로 상세
GET  /api/mobility/ddareungi/stations    따릉이 대여소 조회
GET  /api/mobility/kickboard/nearby      근처 킥보드 조회
GET  /api/places/search                  장소 검색 (네이버 API 프록시)
```

### 경로 탐색 API

```
GET /api/routes
  ?originLat=37.5665
  &originLng=126.9780
  &destLat=37.4979
  &destLng=127.0276
  &mobility=BIKE,KICKBOARD     ← 사용 가능한 이동수단
  &ownedMobility=BIKE          ← 소유 기기
```

```json
{
  "routes": [
    {
      "routeId": "rt_001",
      "type": "TRANSIT_WITH_KICKBOARD",
      "totalMinutes": 27,
      "totalCostWon": 1850,
      "score": 0.82,
      "recommended": true,
      "legs": [
        {
          "type": "TRANSIT",
          "mode": "BUS",
          "lineName": "간선 140",
          "durationMinutes": 18,
          "distanceMeters": 4200,
          "start": { "name": "서울역", "lat": 37.5547, "lng": 126.9706 },
          "end": { "name": "반포역", "lat": 37.5040, "lng": 127.0050 }
        },
        {
          "type": "KICKBOARD",
          "mode": "KICKBOARD_SHARED",
          "durationMinutes": 9,
          "distanceMeters": 1800,
          "mobilityInfo": {
            "mobilityType": "KICKBOARD_SHARED",
            "operatorName": "씽씽",
            "batteryLevel": 82,
            "lat": 37.5038,
            "lng": 127.0048,
            "distanceMeters": 120
          }
        }
      ],
      "comparedToTransitOnly": {
        "originalMinutes": 45,
        "savedMinutes": 18
      }
    }
  ]
}
```

---

## 7. 백엔드 패키지 구조

```
application/
├── route/
│   ├── RouteOptimizationService     ← 핵심 알고리즘
│   ├── CandidatePointSelector       ← 후보 지점 필터링
│   └── RouteScoreCalculator         ← 점수 계산
├── mobility/
│   ├── DdareungiService             ← 따릉이 조회
│   └── KickboardService             ← 킥보드 조회 (국토부 TAGO)
└── external/
    ├── OdsayRouteClient             ← ODsay API (WebClient)
    ├── NaverDirectionsClient        ← 네이버 자전거 경로 (WebClient)
    ├── DdareungiApiClient           ← 따릉이 API (WebClient)
    └── KickboardApiClient           ← 국토부 TAGO API (WebClient)

domain/
├── route/
│   ├── Route
│   ├── Leg
│   ├── LegType
│   └── RouteType
├── mobility/
│   ├── MobilityInfo
│   └── MobilityType
└── location/
    └── Location
```

---

## 8. 프론트엔드 구조 (React)

### 페이지

```
/              → 메인 (출발지/목적지 입력 + 지도)
/routes        → 경로 결과 목록
/routes/:id    → 경로 상세 (단계별 안내)
```

### 컴포넌트 구조

```
src/
├── pages/
│   ├── MainPage.jsx
│   ├── RouteListPage.jsx
│   └── RouteDetailPage.jsx
├── components/
│   ├── map/
│   │   ├── NaverMap.jsx
│   │   ├── RoutePolyline.jsx
│   │   └── MobilityMarker.jsx
│   ├── route/
│   │   ├── RouteCard.jsx
│   │   ├── LegItem.jsx
│   │   └── MobilityBadge.jsx
│   └── search/
│       ├── PlaceSearchInput.jsx
│       └── MobilitySelector.jsx
├── hooks/
│   ├── useRouteSearch.js
│   ├── useNaverMap.js
│   └── useGeolocation.js
└── api/
    └── routeApi.js
```

### 기술 스택

| 항목 | 기술 |
|------|------|
| 프레임워크 | React 18 |
| 라우팅 | React Router v6 |
| HTTP | Axios |
| 지도 | Naver Maps JS SDK |
| 스타일링 | TailwindCSS |

---

## 9. 기술 스택 요약

| 항목 | 기술 |
|------|------|
| 백엔드 | Java 21 + Spring Boot 3 |
| HTTP 클라이언트 | Spring MVC + WebClient (병렬 처리) |
| 빌드 | Gradle 멀티모듈 (4모듈) |
| 프론트엔드 | React 18 + TailwindCSS |
| 지도 | 네이버 지도 API |
| 대중교통 라우팅 | ODsay API |
| 공유자전거 | 서울 공공데이터 따릉이 API |
| 공유킥보드 | 국토부 TAGO 공유 퍼스널모빌리티 API |

---

## 10. MVP 구현 순서 (권장)

```
Phase 1: 백엔드 기반
  1. 프로젝트 셋업 (Gradle 멀티모듈)
  2. ODsay API 연동 (기본 대중교통 경로)
  3. 도메인 모델 정의 (Route, Leg 등)
  4. 핵심 알고리즘 구현 (CandidatePointSelector, RouteOptimizationService)
  5. 따릉이 API 연동
  6. 국토부 킥보드 API 연동
  7. WebClient 병렬 처리 적용

Phase 2: 프론트엔드
  8. React 프로젝트 셋업
  9. 네이버 지도 연동
  10. 경로 탐색 UI
  11. 경로 결과 표시 + 지도 폴리라인

Phase 3: 통합 및 개선
  12. 스코어링 튜닝
  13. 에러 처리 / 폴백 로직
  14. 성능 최적화
```
