# 설계 문서: 모든수단 최적 탐색 (searchMode=OPTIMAL)

**작성일:** 2026-03-05
**상태:** 승인됨

---

## 배경 및 목적

기존 구현은 사용자가 이동수단(따릉이/킥보드/개인자전거)을 먼저 선택하면
`대중교통 → 선택한 이동수단(라스트마일)` 패턴 하나만 탐색한다.

이 설계는 이동수단을 선택하지 않아도 알고리즘이 스스로 모든 수단 × 모든 패턴을
탐색하여 진짜 최적 경로를 추천하는 **`searchMode=OPTIMAL`** 모드를 추가한다.

---

## 설계 결정: Strategy 패턴 + searchMode 파라미터

### 선택하지 않은 이유

| 방안 | 탈락 이유 |
|------|-----------|
| `OPTIMAL_ALL` MobilityType 추가 | 이동수단 타입과 검색 모드 개념이 섞임 |
| 별도 엔드포인트 `/api/routes/optimal` | 컨트롤러 로직 중복, 관리 포인트 증가 |

### 선택한 방안

```
GET /api/routes?searchMode=SPECIFIC&mobility=KICKBOARD_SHARED  ← 기존 동작
GET /api/routes?searchMode=OPTIMAL                             ← 신규
```

`RouteOptimizationService`가 `searchMode`에 따라 Strategy를 선택한다.

---

## UI 변경

```
[🚲 따릉이]  [🛴 킥보드]  [🚴 개인자전거]  [🔍 최적 탐색]
```

- 기존 3개 버튼: 다중 선택 가능 → `searchMode=SPECIFIC`
- **🔍 최적 탐색**: 단독 선택만 허용 (선택 시 다른 버튼 해제) → `searchMode=OPTIMAL`
- 아무것도 선택 안 함: 대중교통만 (`searchMode=SPECIFIC`, mobility 없음)

---

## 백엔드 알고리즘: OPTIMAL 모드

### Strategy 인터페이스

```java
// application/route/strategy/RouteSearchStrategy.java
public interface RouteSearchStrategy {
    Mono<List<Route>> search(Location origin, Location destination);
}
```

### SpecificMobilityStrategy (기존 로직 이동)

기존 `RouteOptimizationService`의 `generateCombinedRoutes()` 로직을 그대로 이동.
사용자가 선택한 수단으로 **라스트마일(패턴 C)** 만 탐색.

### OptimalSearchStrategy (신규)

**입력:** origin, destination
**출력:** 최대 5개 경로 (점수 내림차순)

```
① ODsay API → 기본 대중교통 경로 + 소요시간 (베이스라인)

② 3가지 수단(DDAREUNGI / KICKBOARD_SHARED / PERSONAL) × 4가지 패턴
   병렬(Flux.merge) 탐색:

   패턴 B  [퍼스트마일]
     - origin 반경 500m 내 이동수단 확인
     - 있으면: 이동수단으로 첫 대중교통 정류장까지 이동 시간 계산
     - 경로: mobility(origin→첫정류장) + transit(첫정류장→destination)

   패턴 C  [라스트마일] ← 기존 SPECIFIC 모드와 동일
     - 기본 경로 30~80% 구간 정류장을 후보로
     - 각 후보 근처 이동수단 확인
     - 경로: transit(origin→환승점) + mobility(환승점→destination)

   패턴 D  [퍼스트+라스트마일]
     - 패턴 B + 패턴 C 조합
     - 경로: mobility + transit(중간구간) + mobility

   패턴 E  [이동수단만]
     - haversine(origin, destination) < 수단별 최대범위
       (따릉이 10km, 킥보드 5km, 개인 15km)
     - 조건 충족 시: mobility(origin→destination) 단독

③ 가용성 필터
   - DDAREUNGI: 따릉이 API 재고 확인
   - KICKBOARD_SHARED: TAGO API 재고 확인
   - PERSONAL: 항상 가용

④ 점수 계산 (RouteScoreCalculator 재사용)
   score = 시간(0.5) + 환승횟수(0.2) + 비용(0.2) + 체력소모(0.1)
   모든 값 0~1 정규화

⑤ 전체 후보 정렬 → 상위 5개 → 1위 ⭐️ 추천
```

### 병렬 처리 구조 (Reactor)

```
Mono<List<Leg>> baseLegs = transitRoutePort.getTransitRoute(origin, dest)

Flux.fromIterable([DDAREUNGI, KICKBOARD_SHARED, PERSONAL])
  .flatMap(type ->
    Flux.merge(
      patternB(origin, dest, type, baseLegs),
      patternC(origin, dest, type, baseLegs),
      patternD(origin, dest, type, baseLegs),
      patternE(origin, dest, type)
    )
  )
  .mergeWith(Mono.just(baseRoute))   // 패턴 A: 대중교통만
  .collectList()
  .map(candidates -> rank(candidates, baseMinutes))
```

---

## 도메인 모델 변경

### RouteType 추가

```java
public enum RouteType {
    TRANSIT_ONLY,
    TRANSIT_WITH_BIKE,          // 기존 (라스트마일 자전거)
    TRANSIT_WITH_KICKBOARD,     // 기존 (라스트마일 킥보드)
    BIKE_FIRST_TRANSIT,         // 기존 (미사용)
    MOBILITY_FIRST_TRANSIT,     // 신규: 퍼스트마일
    MOBILITY_TRANSIT_MOBILITY,  // 신규: 퍼스트 + 라스트마일
    MOBILITY_ONLY               // 신규: 이동수단만
}
```

---

## 변경 파일 목록

### 백엔드 (application/domain)

| 파일 | 변경 |
|------|------|
| `domain/route/RouteType.java` | 3개 enum 추가 |
| `application/route/strategy/RouteSearchStrategy.java` | 신규 인터페이스 |
| `application/route/strategy/SpecificMobilityStrategy.java` | 기존 로직 이동 |
| `application/route/strategy/OptimalSearchStrategy.java` | 5패턴 신규 구현 |
| `application/route/RouteOptimizationService.java` | 전략 선택자로 슬림화 |
| `application/route/CandidatePointSelector.java` | 퍼스트마일용 메서드 추가 |

### API

| 파일 | 변경 |
|------|------|
| `api/route/RouteController.java` | `searchMode` 파라미터 추가 |

### 프론트엔드

| 파일 | 변경 |
|------|------|
| `frontend/src/components/search/MobilitySelector.jsx` | "🔍 최적 탐색" 버튼 추가, 단독 선택 로직 |
| `frontend/src/pages/MainPage.jsx` | searchMode 상태 관리 + API 파라미터 전달 |
| `frontend/src/api/routeApi.js` | `searchMode` 파라미터 추가 |

---

## 테스트 계획

- `OptimalSearchStrategyTest`: 각 패턴(B/C/D/E)별 단위 테스트
- `SpecificMobilityStrategyTest`: 기존 테스트 리팩터링
- `RouteOptimizationServiceTest`: searchMode에 따른 전략 선택 테스트
- `RouteControllerTest`: searchMode 파라미터 처리 테스트
