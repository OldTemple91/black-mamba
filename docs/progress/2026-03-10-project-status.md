# Black Mamba — 프로젝트 진행 현황

> 최종 업데이트: 2026-03-10
> 목적: 현대/기아 모빌리티 서비스 백엔드 포트폴리오

---

## 프로젝트 개요

**"MaaS(Mobility as a Service) 라우팅 엔진"**
서울 한정, 대중교통 + 따릉이 + 개인 이동수단을 조합해 최적 멀티모달 경로를 탐색하는 백엔드 서비스.

```
[사용자 출발지] ──► [이동수단 퍼스트마일] ──► [대중교통] ──► [이동수단 라스트마일] ──► [목적지]
```

---

## 버전별 진화 타임라인

### v0.1 — 기반 구조 (2026-03-04)
```
[완료] Gradle 멀티모듈 셋업 (api / application / domain / infra)
[완료] 도메인 모델 정의 (Route, Leg, Location, MobilityType 등)
[완료] ODsay API Client — 대중교통 경로 조회
[완료] REST API Controller (/api/routes)
```
**구조:**
```
domain (순수 모델)
    ↑
application (Port 인터페이스 + 비즈니스 로직)
    ↑
infra (외부 API 클라이언트)
    ↑
api (Spring Boot, bootJar)
```

---

### v0.2 — 이동수단 API 연동 (2026-03-05 ~ 06)
```
[완료] 따릉이 API Client — 서울 공공데이터, 실시간 대여소 정보
[완료] TAGO 킥보드 API Client — 공유킥보드 위치 (※ 후에 문제 발생)
[완료] DdareungiStationFilter / KickboardDeviceFilter (반경 500m, 배터리 20% 필터)
[완료] MobilityAvailabilityAdapter — 이동수단 가용성 조회 포트 구현
[완료] MobilityTimeAdapter — Tmap 보행자 경로 API 기반 이동시간 계산
[완료] CandidatePointSelector — ODsay 중간 정류장 30~80% 구간 환승 후보 선택
[완료] RouteScoreCalculator — 시간/환승/비용/피로도 가중 점수 산정
```

**핵심 알고리즘:**
```
ODsay 대중교통 경로 조회
    ↓
중간 30~80% 구간 정류장 추출 (passThroughStations 실좌표 우선, 없으면 선형보간 fallback)
    ↓
각 후보 지점에서 이동수단 + 목적지 경로 재계산 (Tmap API)
    ↓
점수(score) 비교 → 최적 경로 추천
```

---

### v0.3 — 경로 전략 고도화 (2026-03-07)
```
[완료] OptimalSearchStrategy — 패턴 A/B/C/D/E 병렬 탐색
[완료] SpecificMobilityStrategy — 사용자 선택 이동수단 특화 탐색
[완료] RouteOptimizationService — 전략 선택 + 조율
```

**탐색 패턴:**
```
패턴 A: 대중교통만 (기준 경로)
패턴 B: 이동수단 퍼스트마일 → 대중교통
패턴 C: 대중교통 → 이동수단 라스트마일  ← 메인
패턴 D: 이동수단 → 대중교통 → 이동수단 (퍼스트+라스트)
패턴 E: 이동수단만 (직선거리 < 최대범위)
```

**이동수단별 최대 범위:**
| 이동수단 | 최대 범위 |
|---------|---------|
| 따릉이   | 10,000m |
| 킥보드   |  5,000m |

---

### v0.4 — 프론트엔드 + 지도 연동 (2026-03-08)
```
[완료] React 18 + Vite + TailwindCSS 프로젝트 셋업
[완료] 네이버 지도 SDK 연동 (지도 렌더링, 마커)
[완료] 메인 페이지 (장소 검색, 출발/도착 입력)
[완료] 경로 결과 화면 (RouteCard + RouteListPage)
[완료] LegItem 상세 (노선명, 정류장수, 색상 코딩)
[완료] NaverMap 멀티 폴리라인 (Leg별 색상 구분)
[완료] Naver 지오코딩 + 장소 검색 API 클라이언트
```

---

### v0.5 — 버그 수정 + 안정화 (2026-03-09 ~ 10)
```
[완료] Tmap 보행자 응답 역직렬화 버그 수정
[완료] ODsay API 키 URL 인코딩 버그 수정
[완료] TransitInfo.passThroughStations: List<String> → List<Location> 변경
[완료] ODsay rate limit 대응 (429 처리)
[완료] 버스 노선명 표시 수정
[완료] 따릉이/킥보드 API 호출 로깅 추가 (반경, 가용수, 선택 결과)
[완료] TAGO 킥보드 API 엔드포인트 오류 진단
       - 원인: getPMProvider(운영사 목록) → GetPMListByProvider(차량 위치)로 교정
       - 결과: API 자체가 서울 데이터 미제공 확인 → 전략 전환 필요
[완료] Fallback 메트릭 (Micrometer Counter) + Actuator 엔드포인트
[완료] PERSONAL 이동수단 비활성화 (OptimalSearch에서 제외)
```

---

### 현재 상태 (v0.5 기준)

#### 동작하는 기능
| 기능 | 상태 | 비고 |
|------|------|------|
| 대중교통 경로 조회 (ODsay) | ✅ 정상 | rate limit 대응 완료 |
| 따릉이 실시간 정보 (서울 공공데이터) | ✅ 정상 | 대여소 위치 + 대여가능 수 |
| Tmap 자전거/킥보드 경로 시간 계산 | ✅ 정상 | 보행자 경로 기반 |
| 최적 경로 탐색 (패턴 B/C/D/E) | ✅ 정상 | 따릉이 한정 실데이터 |
| 네이버 지도 + 경로 시각화 | ✅ 정상 | 폴리라인 색상 구분 |
| 지오코딩 + 장소 검색 | ✅ 정상 | |

#### 알려진 문제 (버그)
| # | 위치 | 문제 | 심각도 |
|---|------|------|--------|
| B-1 | `OptimalSearchStrategy` | `KICKBOARD_SHARED` 항상 가상 데이터로 경로 생성 (TAGO API 미제공) | 🔴 HIGH |
| B-2 | `RouteType` | `BIKE_FIRST_TRANSIT` 미사용 dead code | 🟡 LOW |
| B-3 | `MobilitySelector.jsx` | UI에 `KICKBOARD_SHARED` 옵션 표시되나 실데이터 없음 | 🟡 MEDIUM |
| B-4 | `MobilityAvailabilityAdapter` | `syntheticKickboard` battery=0 노출 | 🟡 MEDIUM |

---

## 아키텍처 현황도

```
┌─────────────────────────────────────────────────────────────────┐
│  Frontend (React 18 + Vite + TailwindCSS)                       │
│  MainPage → RouteListPage → RouteCard → LegItem                 │
│  NaverMap (폴리라인 색상 구분)                                    │
│  MobilitySelector (따릉이 / 킥보드 / 개인킥보드 / 최적탐색)        │
└──────────────────────┬──────────────────────────────────────────┘
                       │ HTTP GET /api/routes
┌──────────────────────▼──────────────────────────────────────────┐
│  api 모듈                                                        │
│  RouteController → RouteOptimizationService                     │
└──────────────────────┬──────────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────────┐
│  application 모듈                                                │
│                                                                 │
│  RouteOptimizationService                                       │
│    ├── OptimalSearchStrategy (OPTIMAL 모드)                      │
│    │     ├── 패턴B: 이동수단→대중교통                             │
│    │     ├── 패턴C: 대중교통→이동수단                             │
│    │     ├── 패턴D: 이동수단+대중교통+이동수단                     │
│    │     └── 패턴E: 이동수단만                                    │
│    └── SpecificMobilityStrategy (SPECIFIC 모드)                  │
│          └── 패턴C(라스트마일)만 탐색                             │
│                                                                 │
│  CandidatePointSelector (중간 30~80% 구간 환승 후보 선택)          │
│  RouteScoreCalculator (시간0.5 + 환승0.2 + 비용0.2 + 피로도0.1)   │
└──────────────────────┬──────────────────────────────────────────┘
                       │ Port Interface
┌──────────────────────▼──────────────────────────────────────────┐
│  infra 모듈                                                      │
│                                                                 │
│  OdsayTransitRouteAdapter  ──► ODsay API (대중교통 경로)          │
│  MobilityTimeAdapter       ──► Tmap API (자전거/킥보드 경로시간)   │
│  MobilityAvailabilityAdapter                                    │
│    ├── DdareungiApiClient  ──► 서울 공공데이터 (따릉이 실시간) ✅  │
│    └── KickboardApiClient  ──► TAGO API ❌ (서울 데이터 미제공)   │
│  NaverGeocodingClient      ──► 네이버 지오코딩                    │
│  NaverLocalSearchClient    ──► 네이버 장소 검색                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 외부 API 현황

| API | 용도 | 상태 |
|-----|------|------|
| ODsay | 대중교통 경로 조회 | ✅ 정상 (rate limit 429 대응) |
| 서울 공공데이터 따릉이 | 따릉이 대여소 실시간 | ✅ 정상 |
| TAGO GetPMListByProvider | 공유킥보드 위치 | ❌ 서울 데이터 미제공 |
| Tmap 보행자 경로 | 자전거/킥보드 이동시간 | ✅ 정상 |
| 네이버 지오코딩 | 주소 → 좌표 | ✅ 정상 |
| 네이버 장소 검색 | 장소명 검색 | ✅ 정상 |
| 네이버 지도 JS SDK | 지도 렌더링 | ✅ 정상 |

---

## 도메인 모델 현황

```java
// 이동수단 타입
enum MobilityType { PERSONAL, DDAREUNGI, KICKBOARD_SHARED }
//                  ↑ 비활성화   ↑ 실데이터   ↑ 가상데이터(TAGO 미제공)

// 경로 타입
enum RouteType {
    TRANSIT_ONLY,               // 대중교통만
    TRANSIT_WITH_BIKE,          // (legacy, SpecificStrategy에서만 사용)
    TRANSIT_WITH_KICKBOARD,     // (legacy, SpecificStrategy에서만 사용)
    BIKE_FIRST_TRANSIT,         // (dead code - 미사용)
    MOBILITY_FIRST_TRANSIT,     // 이동수단→대중교통 (OptimalStrategy)
    MOBILITY_TRANSIT_MOBILITY,  // 이동수단+대중교통+이동수단 (OptimalStrategy)
    MOBILITY_ONLY               // 이동수단만 (OptimalStrategy)
}

// Leg 타입
enum LegType { TRANSIT, WALK, BIKE, KICKBOARD }
```
