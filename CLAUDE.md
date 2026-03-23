# Black Mamba — MaaS 라우팅 엔진

허브 기반 신뢰도 인식 MaaS(Mobility as a Service) 라우팅 엔진.
대중교통 + 따릉이 + 개인 PM(전기자전거/전동킥보드)의 최적 멀티모달 조합을 탐색.

## 프로젝트 개요

- **목적:** 서울 한정, 대중교통과 다양한 이동수단을 통합해 최적 멀티모달 경로를 탐색하는 MaaS 백엔드 엔진
- **핵심 차별점:**
  1. **허브 기반 경로 탐색** — 모빌리티 허브(따릉이 정류소, 카셰어존 등)를 환승 거점으로 고려해 후보 생성
  2. **신뢰도 기반 다차원 스코어링** — 시간뿐 아니라 따릉이 재고, 반납 확인, 도보 거리까지 반영
  3. **이중 추천 축** — RELIABILITY(안정 우선) vs TIME_PRIORITY(시간 우선) 사용자 선호 반영
  4. **확장 가능한 도메인 설계** — MobilityType/HubType 추가 시 전략 코드 변경 불필요
- **스택:** Java 21, Spring Boot 3.3, Gradle 멀티모듈, WebClient(Reactive), JUnit 5, Micrometer | React 18, Vite, TailwindCSS, 네이버 지도 SDK
- **포지셔닝:** EV/자동차 제조사 모빌리티 서비스 백엔드 경력 포트폴리오

## 모듈 구조

```
black-mamba (루트)
├── api         → @RestController, bootJar, 입력값 검증, 타임아웃 처리
├── application → 비즈니스 로직 (Port, Strategy, Evaluator, HubSelector, MobilitySegmentBuilder)
├── domain      → 순수 도메인 모델 (Route, Leg, Hub, MobilityInfo, RouteEvaluation 등, 의존성 없음)
├── infra       → 외부 API 클라이언트 (ODsay, 따릉이, Tmap, 네이버, TAGO)
└── frontend    → React SPA (장소 검색, 경로 결과, 지도 시각화)
```

의존성 방향: `api → application → domain ← infra`

## 주요 도메인 모델

```java
// 이동수단 타입
enum MobilityType {
    DDAREUNGI,          // 공공 따릉이 (실 API, 15 km/h)
    PERSONAL_EBIKE,     // 개인 전기자전거 (API 불필요, 22 km/h)
    PERSONAL_KICKBOARD, // 개인 전동킥보드 (API 불필요, 20 km/h)
    KICKBOARD_SHARED    // 공유 킥보드 (TAGO API — 서울 데이터 미제공, 호출 차단)
}

// 경로 타입
enum RouteType {
    TRANSIT_ONLY,               // 대중교통만
    TRANSIT_WITH_BIKE,          // 대중교통 + 따릉이 라스트마일
    TRANSIT_WITH_KICKBOARD,     // 대중교통 + 킥보드 라스트마일
    MOBILITY_FIRST_TRANSIT,     // 퍼스트마일 → 대중교통
    MOBILITY_TRANSIT_MOBILITY,  // 이동수단 + 대중교통 + 이동수단
    MOBILITY_ONLY               // 이동수단만
}

// 허브 타입 (환승 거점)
enum HubType {
    SUBWAY_STATION, BUS_STOP, BIKE_STATION,
    MOBILITY_TRANSFER_POINT,    // 이동수단 환승 지점
    CARSHARE_ZONE,              // 카셰어 존 (확장용)
    CHARGING_STATION, PARKING   // 미래 확장용
}

// 핵심 레코드
record Route(routeId, type, totalMinutes, costBreakdown, evaluation, insights, legs, ...)
record Leg(type, mode, durationMinutes, distanceMeters, start, end, transitInfo, mobilityInfo, routeCoordinates)
record RouteEvaluation(timeScore, transferScore, costScore, walkingScore, accessWalkScore, reliabilityScore, totalScore, preferenceMode, ...)
record RouteInsights(recommendationReasons, riskBadges, generationDiagnostics, fallbackDiagnostics)
record Hub(hubId, name, type, location, metadata)
```

## 스코어링 체계

6차원 가중 점수 (0.0 ~ 1.0, 높을수록 좋음):

| 항목 | RELIABILITY | TIME_PRIORITY | 근거 |
|------|-----------|---------------|------|
| 시간 | 0.40 | 0.72 | MaaS 연구 AHP 기반 |
| 환승 | 0.15 | 0.08 | 서울 평균 환승 대기 13.3분 |
| 비용 | 0.10 | 0.03 | 서울 대중교통 비용 차이 적음 |
| 도보 | 0.10 | 0.03 | |
| 접근도보 | 0.10 | 0.02 | 이동수단 픽업까지 도보 |
| 신뢰도 | 0.15 | 0.12 | 따릉이 재고, 반납, 배터리 등 |

신뢰도 페널티 7건: 반납 미확인(-0.35), 대여 부족(-0.15), 배터리 부족(-0.15), 공유수단(-0.10), 대여소 접근(-0.12), 허브 우회(-0.08), 접근도보(-0.15)

상세 산정 근거: `docs/scoring/2026-03-23-reliability-scoring-rationale.md`

## 외부 API

| API | 용도 | 상태 |
|-----|------|------|
| ODsay | 대중교통 경로 조회 | ✅ 정상 (rate limit, TTL 캐시) |
| 서울 공공데이터 따릉이 | 대여소 실시간 가용 | ✅ 정상 (snapshot 캐시, stale fallback) |
| Tmap 보행자 경로 | 자전거/킥보드 이동거리·경로 | ✅ 정상 (quota backoff) |
| 네이버 지오코딩/장소 검색 | 주소→좌표, 장소 자동완성 | ✅ 정상 |
| TAGO GetPMListByProvider | 공유킥보드 위치 | ❌ 서울 데이터 미제공 (호출 차단) |

환경변수: `ODSAY_API_KEY`, `DDAREUNGI_API_KEY`, `TAGO_API_KEY`, `TMAP_APP_KEY`, `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`

## 알고리즘 핵심 흐름

```
1. ODsay API → 대중교통 기본 경로(baseline) 조회
2. CandidatePointSelector → 중간 30~80% 구간 정류장 추출
3. HubSelector → 따릉이 정류소 근접 정류장을 환승 허브로 선택
4. 패턴 B/C/D/E 병렬 탐색 (Mono/Flux):
   - B: 이동수단 퍼스트마일 → 대중교통
   - C: 대중교통 → 이동수단 라스트마일 (메인)
   - D: 이동수단 → 대중교통 → 이동수단
   - E: 이동수단만 (직선거리 < 최대범위)
5. RouteScoreCalculator → 6차원 가중 점수 산정
6. RouteInsightFactory → 추천 이유, 리스크 배지 생성
7. 상위 5개 경로 반환 (대중교통 단독 항상 포함)
```

## 빌드/테스트

```bash
./gradlew assemble          # 전체 빌드
./gradlew test              # 전체 테스트
./gradlew :domain:test      # 도메인 모듈 테스트
./gradlew :application:test # 비즈니스 로직 테스트

# 프론트엔드
cd frontend && npm install && npm run dev
```

## 코드 컨벤션

- **Lombok 금지** — Java record 또는 명시적 getter/setter
- **@SpringBootTest 금지** — 단위 테스트만 (MockitoExtension 또는 직접 객체 생성)
- **WebClient.Builder** — 생성자 주입
- **필터 로직** — 별도 클래스 분리 (`DdareungiStationFilter`, `KickboardDeviceFilter`)
- **캐시** — API별 ConcurrentHashMap + TTL + Micrometer 메트릭
- **Port-Adapter** — application 인터페이스 → infra 구현

## 문서 구조

```
docs/
├── plans/
│   ├── 2026-03-04-implementation-plan.md   # 초기 구현 계획
│   └── 2026-03-10-next-steps.md            # 향후 로드맵 (Phase 1~4)
├── progress/
│   └── 2026-03-10-project-status.md        # 버전별 진화 타임라인
├── scoring/
│   └── 2026-03-23-reliability-scoring-rationale.md  # 스코어링 산정 근거
└── experiments/                             # 허브 선택 실험 기록
```

## 향후 확장 설계

- **MobilityHub 모델:** 카셰어 존을 이동수단(Leg)이 아닌 환승 거점 가중치로 활용
  - TAGO 카셰어 API(존 위치) → CandidatePointSelector 허브 가중치
  - 실시간 재고 연동은 사업자 파트너십 API(카셰어 사업자/카셰어 사업자) 필요
- **날씨 연동:** 기상청 API → 우천 시 이동수단 경로 신뢰도 감소
- **시간대 인식:** 출퇴근 러시아워 대중교통 지연/따릉이 재고 변동 반영

## 다른 환경에서 이어서 작업하는 법

```bash
git clone https://github.com/OldTemple91/black-mamba.git
cd black-mamba
# Claude Code 시작 후:
# "CLAUDE.md를 읽고 docs/plans/2026-03-10-next-steps.md에서 다음 Phase를 확인해서 이어서 작업해줘"
```
