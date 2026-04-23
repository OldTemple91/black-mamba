# ADR-007: Baseline-Guided Multimodal Recomposition 채택

## Status
Accepted (2026-04-23)

## Context

Black Mamba 는 **대중교통 + 공유/개인 이동수단을 결합한 복합 경로** 를 생성·평가해야 한다.
입력은 OD 쌍 (37.4979, 127.0276) → (37.5573, 126.9246), 출력은 5개의 후보 경로 + 점수.

선택지는 두 층에 걸쳐 존재한다:

**L1 — 도로 그래프 최단경로** (A*/Dijkstra/CH/ALT, RAPTOR/CSA)
- 개별 교통 노드/엣지를 직접 탐색
- 도로망·시간표 데이터 필요
- 대표 구현: OSRM, Valhalla, GraphHopper, OpenTripPlanner

**L2 — 다중 이동수단 오케스트레이션**
- L1 결과를 재조합해 사용자에게 의미 있는 "조합 경로" 생성
- 표준화된 알고리즘이 없음 (산업 전체가 각자 구현)

제약사항:
- **ODsay (대중교통)**, **Tmap (보행자)** 등 외부 엔진이 L1 을 이미 처리해 준다
- 포트폴리오 규모에서 서울시 전체 OSM 그래프를 로딩·운영할 부담이 크다
- 사용자가 보는 가치는 "몇 분 걸려" 가 아니라 **"자가용 대신 이렇게 가면 뭐가 좋은지"**
- 실측 ODsay 캐시 히트율이 80%+ 여서 외부 호출 비용이 운영 리스크 아님 (B-3)

이 맥락에서 "L1 을 다시 구현" 할 이유가 없다 — 핵심은 **그 위 L2 층을 어떻게 설계할 것인가**.

## Decision

**Baseline-Guided Multimodal Recomposition** 을 채택한다.

1. **Baseline 생성**: ODsay 에게 "순수 대중교통 경로(baseline)" 를 요청. 이 경로가 곧 **설계도**.
2. **5패턴 병렬 재조합**: baseline 을 기준으로 다음 5가지 변형을 병렬 생성:
   - A: TRANSIT_ONLY (baseline 그대로)
   - B: FIRST_MILE (출발→정류장 이동수단 + 대중교통)
   - C: LAST_MILE (대중교통 + 정류장→도착 이동수단)
   - D: FULL_MIXED (이동수단 + 대중교통 + 이동수단)
   - E: MOBILITY_ONLY (직선거리 < 수단 최대범위)
3. **Post-Process 재랭킹**: Accessibility / Weather / Carbon 을 **침투하지 않는 후처리** 로 적용.
4. **6차원 가중 스코어링**: time/transfer/cost/walk/accessWalk/reliability 를 2 프로파일 (RELIABILITY / TIME_PRIORITY) 가중합.
5. **2단계 도보 계산**: 넓은 필터링은 Haversine 으로 싸게, 확정 후 Tmap 보행 API 로 정밀.

상세 알고리즘 카탈로그: [`docs/architecture/routing-algorithm.md`](../architecture/routing-algorithm.md)

## Consequences

**Pro:**
- **외부 엔진 재활용**: ODsay/Tmap 이 이미 검증된 L1 결과를 제공 → 자체 OSM 운영 0
- **탐색공간 제어**: baseline 근처만 후보 → 수백만 조합 → 수십 개로 축소
- **사용자 맥락 보존**: "2호선 → 홍대입구 → 따릉이 5분" 같은 **스토리 있는 경로** 가 자연스럽게 생성
- **후처리 재사용성**: Accessibility(C-3) / Weather(A-4) / Carbon(C-2) 모두 동일 패턴으로 확장, 알고리즘 복잡도 폭발 없음
- **실패 회복**: ODsay 실패 시 Haversine 추정값으로 합성 (서비스 연속성)
- **설명 가능성**: `GenerationDiagnostic` 으로 "왜 혼합 경로가 안 만들어졌나" 를 사용자에게 노출

**Con:**
- ODsay **응답 품질에 의존** — baseline 이 나쁘면 파생 경로도 나쁨
- 서울 외 지역 확장 시 해당 지역 대중교통 API 필요 (ODsay 는 한국만)
- L1 을 자체 소유하지 않아 **요율 제한(rate limit)에 간접 영향** — B-3 Geohash 캐싱으로 완화 (히트율 80%+)
- 5패턴 고정 구조 — 새 패턴 추가는 `OptimalSearchStrategy` 수정 필요

## Alternatives Considered

### 대안 A — 자체 Graph Search (OSRM + Valhalla)
서울 OSM 도로망 + GTFS 시간표를 로딩해 A\*/RAPTOR 로 직접 탐색.

- **장점**: 외부 의존성 0, rate-limit 해방, 알고리즘 학습 증명
- **단점**:
  - 서울시 OSM 데이터 ~2GB + GTFS ~500MB, 업데이트·품질 관리 상시 필요
  - 컨테이너 리소스 부담 (OSRM ~2GB RAM, 인덱싱 수 분)
  - **문제는 "최단경로" 가 아니라 "조합 경로" 이며, 자체 그래프를 쥐어도 L2 층이 여전히 필요**
- **채택 안 함** — 포트폴리오 규모에서 운영 부담 대비 이득 미미. 단, ODsay rate-limit 이 병목이 되면 재검토.

### 대안 B — Brute Force All Combinations
서울 내 모든 정류장 × 이동수단 조합을 전수 생성 후 필터.

- **장점**: 이론적으로 모든 가능성 포함
- **단점**:
  - 서울 지하철역 339개 × 이동수단 4종 × firstMile × lastMile = **수백만 조합**
  - O(n²) 이상의 탐색 공간 → p95 응답시간 분 단위 악화
  - 대부분이 무의미한 조합 (예: 출발점 반대편 역 환승)
- **채택 안 함** — 의미 있는 복합 경로만 생성하는 휴리스틱이 필요.

### 대안 C — Single-Pattern Only (오직 LAST_MILE)
복잡도 최소화를 위해 LAST_MILE 1개 패턴만 생성.

- **장점**: 구현 단순, 디버깅 쉬움
- **단점**:
  - 아파트 출발 → 역 근처 도착 같은 **FIRST_MILE 우세 케이스** 놓침
  - 실측 (A-5) 에서 아파트 출발 70% 가 Mixed 승리 — 옵션 1개는 사용자 선택권 0
  - 자가용 vs MaaS 비교 스토리 약함
- **채택 안 함** — 사용자 OD 다양성이 5패턴을 요구함 (실측 증거).

### 대안 D — Machine Learning 기반 조합 생성
과거 경로 이력을 학습시켜 조합 패턴을 ML 로 추론.

- **장점**: 개인화, 이론적 최적
- **단점**:
  - 훈련 데이터 필요 (포트폴리오 단계엔 부족)
  - 블랙박스 — 실패 시 이유 설명 불가 (C-3 Accessibility 같은 정책 제약 적용 어려움)
  - 현 단계에선 **룰 기반이 설명 가능성 측면에서 우월**
- **채택 안 함 (현재)** — 데이터 축적 후 재랭킹에 ML 도입은 고려 (로드맵 E-2).

## Related

- **Architecture Catalog**: [`docs/architecture/routing-algorithm.md`](../architecture/routing-algorithm.md) — 8개 자체 알고리즘 상세
- **Implementation**:
  - `application/.../strategy/OptimalSearchStrategy.java` — 5패턴 병렬 생성
  - `application/.../strategy/SpecificMobilityStrategy.java` — 사용자 명시 모드
  - `application/.../HubSelector.java` — 2-Phase Primary/Fallback
  - `application/.../RouteScoreCalculator.java` — 6차원 가중 스코어링
- **Improvements**:
  - [B-3 Geohash Spatial Caching](../improvements/2026-04-17-B3-geohash-spatial-caching.md) — 외부 API 의존 완화
  - [C-3 Accessibility](../improvements/2026-04-20-C3-accessibility.md) — 후처리 패턴 최초 도입
  - [A-4 Weather-aware](../improvements/2026-04-23-A4-weather-aware-routing.md) — 후처리 패턴 재사용 증명
  - [A-5 Real User Benchmark](../improvements/2026-04-23-A5-real-user-benchmark.md) — 5패턴 가치의 실측 증거
- **See also**:
  - ADR-002 (TAGO → Personal PM pivot) — 외부 API 의존 조정 사례
  - ADR-003 (Carshare as Hub) — 도메인 모델링 동일 철학 (데이터 타입에 정책을 가두지 않기)
