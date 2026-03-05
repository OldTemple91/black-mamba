# Black Mamba — CLAUDE.md

멀티모달 네비게이션 서비스 (대중교통 + 따릉이/공유킥보드 조합 최적 경로).

## 프로젝트 개요

- **목적:** 서울 한정, 대중교통 + 개인 이동수단을 조합한 경로를 탐색해 기존 대중교통만 쓰는 경로보다 빠른 루트를 제안
- **핵심 알고리즘:** ODsay API로 기본 경로 조회 → 중간 30~80% 구간 정류장을 후보로 선택 → 후보 지점에서 이동수단 + 목적지 경로 재계산 → 점수 비교 후 최적 경로 추천
- **스택:** Java 21, Spring Boot 3.3, Gradle 멀티모듈, WebClient, JUnit 5 (백엔드) + React 18 + Vite + TailwindCSS + 네이버 지도 SDK (프론트엔드)

## 모듈 구조

```
black-mamba (루트)
├── api         → @RestController, bootJar 생성
├── application → 비즈니스 로직 (Port 인터페이스, Service, Selector, Calculator)
├── domain      → 순수 도메인 모델 (Route, Leg, Location 등, 의존성 없음)
└── infra       → 외부 API 클라이언트 (ODsay, 따릉이, TAGO 킥보드)
```

의존성 방향: `api → application → domain ← infra`

## 주요 도메인 모델 (domain 모듈)

```java
record Location(String name, double lat, double lng) {}
record Leg(LegType type, String mode, int durationMinutes, int distanceMeters,
           Location start, Location end, TransitInfo transitInfo, MobilityInfo mobilityInfo) {}
record Route(String routeId, RouteType type, int totalMinutes, int totalCostWon,
             double score, boolean recommended, List<Leg> legs, Comparison comparison) {}
enum LegType { TRANSIT, WALK, BIKE, KICKBOARD }
enum RouteType { TRANSIT_ONLY, WITH_BIKE, WITH_KICKBOARD }
enum MobilityType { DDAREUNGI, KICKBOARD_SHARED, PERSONAL_BIKE, PERSONAL_KICKBOARD }
```

## 외부 API

| API | 용도 | 키 환경변수 |
|-----|------|-----------|
| ODsay (`api.odsay.com`) | 대중교통 경로 조회 | `ODSAY_API_KEY` |
| 서울 공공데이터 (`openapi.seoul.go.kr`) | 따릉이 대여소 실시간 | `DDAREUNGI_API_KEY` |
| 국토부 TAGO (`apis.data.go.kr`) | 공유 킥보드 실시간 위치 | `TAGO_API_KEY` |
| 네이버 지도 SDK | 지도 렌더링, 자전거 경로 | `NAVER_CLIENT_ID` |

## 구현 완료 현황

| Task | 상태 | 설명 |
|------|------|------|
| Task 1 | ✅ | Gradle 멀티모듈 셋업 |
| Task 2 | ✅ | 도메인 모델 정의 |
| Task 3 | ✅ | ODsay API Client (`infra/odsay/`) |
| Task 4 | ✅ | 따릉이 API Client (`infra/ddareungi/`) |
| Task 5 | ✅ | TAGO 킥보드 API Client (`infra/kickboard/`) |
| Task 6 | ⬜ | CandidatePointSelector (`application/route/`) |
| Task 7 | ⬜ | RouteScoreCalculator (`application/route/`) |
| Task 8 | ⬜ | RouteOptimizationService — 핵심 알고리즘 |
| Task 9 | ⬜ | REST API Controller (`api/`) |
| Task 10 | ⬜ | React 프로젝트 셋업 (`frontend/`) |
| Task 11 | ⬜ | 네이버 지도 연동 + 메인 화면 |
| Task 12 | ⬜ | 경로 결과 화면 |
| Task 13 | ⬜ | 백엔드-프론트엔드 통합 확인 |

상세 구현 계획: `docs/plans/2026-03-04-implementation-plan.md`

## 빌드/테스트 명령

```bash
./gradlew assemble          # 전체 빌드
./gradlew :infra:test       # infra 모듈 테스트
./gradlew :application:test # application 모듈 테스트
./gradlew :domain:test      # domain 모듈 테스트
./gradlew test              # 전체 테스트
```

## 코드 컨벤션

- **Lombok 금지** — Java record 또는 명시적 getter/setter 사용
- **@SpringBootTest 금지** — 단위 테스트만 작성 (MockitoExtension 또는 직접 객체 생성)
- **WebClient.Builder** — 생성자 주입 (null 체크 없음, Spring이 자동 주입)
- **필터 로직** — 별도 클래스로 분리 (`DdareungiStationFilter`, `KickboardDeviceFilter` 패턴)
- **TDD 원칙** — 테스트 먼저 작성 → 실패 확인 → 구현 → 통과 확인

## 다른 환경에서 이어서 작업하는 법

```bash
git clone https://github.com/OldTemple91/black-mamba.git
cd black-mamba
# Claude Code 시작 후:
# "CLAUDE.md와 docs/plans/2026-03-04-implementation-plan.md를 읽고
#  미완료 Task부터 이어서 TDD로 구현해줘"
```
