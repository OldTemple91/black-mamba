# 모든수단 최적탐색 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** `searchMode=OPTIMAL` 파라미터를 추가해 5가지 이동 패턴(A~E) × 3가지 수단을 병렬 탐색하는 최적 경로 추천 기능 구현.

**Architecture:** Strategy 패턴으로 기존 `SpecificMobilityStrategy`와 신규 `OptimalSearchStrategy`를 분리. `RouteOptimizationService`는 `searchMode`에 따라 전략을 선택하는 컨텍스트 역할만 수행.

**Tech Stack:** Java 21 record, Spring Boot 3.3, Reactor (Mono/Flux), JUnit 5 + Mockito, React 18 + Vite

---

## Task 1: SearchMode enum + RouteType 확장

**Files:**
- Modify: `domain/src/main/java/com/blackmamba/navigation/domain/route/RouteType.java`
- Create: `application/src/main/java/com/blackmamba/navigation/application/route/SearchMode.java`

**Step 1: RouteType에 3개 값 추가**

```java
// domain/src/main/java/com/blackmamba/navigation/domain/route/RouteType.java
package com.blackmamba.navigation.domain.route;

public enum RouteType {
    TRANSIT_ONLY,
    TRANSIT_WITH_BIKE,
    TRANSIT_WITH_KICKBOARD,
    BIKE_FIRST_TRANSIT,
    MOBILITY_FIRST_TRANSIT,     // 신규: 퍼스트마일 (이동수단→대중교통)
    MOBILITY_TRANSIT_MOBILITY,  // 신규: 퍼스트+라스트 (이동수단→대중교통→이동수단)
    MOBILITY_ONLY               // 신규: 이동수단만
}
```

**Step 2: SearchMode enum 생성**

```java
// application/src/main/java/com/blackmamba/navigation/application/route/SearchMode.java
package com.blackmamba.navigation.application.route;

public enum SearchMode {
    SPECIFIC,  // 사용자가 이동수단 직접 선택 → 라스트마일만 탐색
    OPTIMAL    // 알고리즘이 5가지 패턴 × 3가지 수단 자동 탐색
}
```

**Step 3: 컴파일 확인**

```bash
cd /Users/sjw/Desktop/black-mamba
./gradlew :domain:compileJava :application:compileJava
```

Expected: `BUILD SUCCESSFUL`

**Step 4: 커밋**

```bash
git add domain/ application/src/main/java/com/blackmamba/navigation/application/route/SearchMode.java
git commit -m "feat: SearchMode enum + RouteType 3가지 패턴 추가"
```

---

## Task 2: RouteSearchStrategy 인터페이스 생성

**Files:**
- Create: `application/src/main/java/com/blackmamba/navigation/application/route/strategy/RouteSearchStrategy.java`

**Step 1: 인터페이스 작성**

```java
// application/src/main/java/com/blackmamba/navigation/application/route/strategy/RouteSearchStrategy.java
package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Route;
import reactor.core.publisher.Mono;

import java.util.List;

public interface RouteSearchStrategy {
    Mono<List<Route>> search(Location origin, Location destination);
}
```

**Step 2: 컴파일 확인**

```bash
./gradlew :application:compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 3: SpecificMobilityStrategy — 기존 로직 이동 (TDD)

기존 `RouteOptimizationService` 내부 로직을 새 클래스로 이동. 기존 테스트를 SpecificMobilityStrategy 테스트로 마이그레이션.

**Files:**
- Create: `application/src/main/java/com/blackmamba/navigation/application/route/strategy/SpecificMobilityStrategy.java`
- Create: `application/src/test/java/com/blackmamba/navigation/application/route/strategy/SpecificMobilityStrategyTest.java`

**Step 1: 테스트 작성 (RED)**

```java
// application/src/test/java/com/blackmamba/navigation/application/route/strategy/SpecificMobilityStrategyTest.java
package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.*;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecificMobilityStrategyTest {

    @Mock TransitRoutePort transitRoutePort;
    @Mock MobilityTimePort mobilityTimePort;
    @Mock MobilityAvailabilityPort mobilityAvailabilityPort;
    @Mock CandidatePointSelector candidatePointSelector;
    @Mock RouteScoreCalculator scoreCalculator;

    @Test
    void 이동수단_없으면_대중교통만_반환한다() {
        Location origin = new Location("서울역", 37.5547, 126.9706);
        Location dest   = new Location("강남역", 37.4979, 127.0276);
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 45, 10000, origin, dest, null, null);

        when(transitRoutePort.getTransitRoute(any(), any()))
                .thenReturn(Mono.just(List.of(leg)));
        when(scoreCalculator.calculate(any())).thenReturn(0.5);

        SpecificMobilityStrategy strategy = new SpecificMobilityStrategy(
                List.of(), transitRoutePort, mobilityTimePort,
                mobilityAvailabilityPort, candidatePointSelector, scoreCalculator);

        List<Route> routes = strategy.search(origin, dest).block();

        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).type()).isEqualTo(RouteType.TRANSIT_ONLY);
        assertThat(routes.get(0).recommended()).isTrue();
    }

    @Test
    void 이동수단_조합_경로가_빠르면_추천으로_표시된다() {
        Location origin    = new Location("서울역", 37.5547, 126.9706);
        Location dest      = new Location("강남역", 37.4979, 127.0276);
        Location candidate = new Location("중간역", 37.5200, 127.0000);
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 45, 10000, origin, dest, null, null);

        when(transitRoutePort.getTransitRoute(any(), any()))
                .thenReturn(Mono.just(List.of(leg)));
        when(transitRoutePort.getTransitTimeMinutes(any(), any())).thenReturn(Mono.just(18));
        when(mobilityTimePort.getMobilityTimeMinutes(any(), any(), any())).thenReturn(Mono.just(9));
        when(mobilityAvailabilityPort.findNearbyMobility(any(Double.class), any(Double.class), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.KICKBOARD_SHARED, "씽씽",
                                "DEV_001", 85, null, 37.52, 127.0, 0, 120))));
        when(candidatePointSelector.select(any(), any())).thenReturn(List.of(candidate));
        when(scoreCalculator.calculate(any())).thenReturn(0.8, 0.5);

        SpecificMobilityStrategy strategy = new SpecificMobilityStrategy(
                List.of(MobilityType.KICKBOARD_SHARED), transitRoutePort, mobilityTimePort,
                mobilityAvailabilityPort, candidatePointSelector, scoreCalculator);

        List<Route> routes = strategy.search(origin, dest).block();

        assertThat(routes.get(0).recommended()).isTrue();
        assertThat(routes.get(0).totalMinutes()).isEqualTo(27);
    }
}
```

**Step 2: 테스트 실패 확인**

```bash
./gradlew :application:test --tests "*.SpecificMobilityStrategyTest"
```

Expected: FAIL — `SpecificMobilityStrategy` 클래스 없음

**Step 3: SpecificMobilityStrategy 구현**

```java
// application/src/main/java/com/blackmamba/navigation/application/route/strategy/SpecificMobilityStrategy.java
package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.*;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 사용자가 선택한 이동수단으로 라스트마일(패턴 C)만 탐색하는 전략.
 * 기존 RouteOptimizationService 로직을 이동.
 */
public class SpecificMobilityStrategy implements RouteSearchStrategy {

    private final List<MobilityType> mobilityTypes;
    private final TransitRoutePort transitRoutePort;
    private final MobilityTimePort mobilityTimePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final CandidatePointSelector candidatePointSelector;
    private final RouteScoreCalculator scoreCalculator;

    public SpecificMobilityStrategy(List<MobilityType> mobilityTypes,
                                     TransitRoutePort transitRoutePort,
                                     MobilityTimePort mobilityTimePort,
                                     MobilityAvailabilityPort mobilityAvailabilityPort,
                                     CandidatePointSelector candidatePointSelector,
                                     RouteScoreCalculator scoreCalculator) {
        this.mobilityTypes = mobilityTypes;
        this.transitRoutePort = transitRoutePort;
        this.mobilityTimePort = mobilityTimePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.candidatePointSelector = candidatePointSelector;
        this.scoreCalculator = scoreCalculator;
    }

    @Override
    public Mono<List<Route>> search(Location origin, Location destination) {
        return transitRoutePort.getTransitRoute(origin, destination)
                .flatMap(baseLegs -> {
                    Route baseRoute = Route.of(baseLegs, RouteType.TRANSIT_ONLY);
                    if (mobilityTypes.isEmpty()) {
                        return Mono.just(List.of(baseRoute.withScore(scoreCalculator.calculate(baseRoute), true)));
                    }
                    return generateCombinedRoutes(baseLegs, origin, destination)
                            .collectList()
                            .map(combined -> rank(baseRoute, combined, baseRoute.totalMinutes()));
                });
    }

    private Flux<Route> generateCombinedRoutes(List<Leg> baseLegs, Location origin, Location destination) {
        return Flux.fromIterable(mobilityTypes)
                .flatMap(type -> {
                    MobilityConfig config = type == MobilityType.KICKBOARD_SHARED
                            ? MobilityConfig.kickboard() : MobilityConfig.bike();
                    List<Location> candidates = candidatePointSelector.select(baseLegs, config);
                    return Flux.fromIterable(candidates)
                            .flatMap(candidate -> buildRoute(origin, candidate, destination, type));
                });
    }

    private Mono<Route> buildRoute(Location origin, Location switchPoint,
                                    Location destination, MobilityType type) {
        Mono<Integer> transitTime  = transitRoutePort.getTransitTimeMinutes(origin, switchPoint);
        Mono<Integer> mobilityTime = mobilityTimePort.getMobilityTimeMinutes(switchPoint, destination, type);
        Mono<java.util.Optional<MobilityInfo>> avail =
                mobilityAvailabilityPort.findNearbyMobility(switchPoint.lat(), switchPoint.lng(), type);

        return Mono.zip(transitTime, mobilityTime, avail)
                .filter(t -> t.getT3().isPresent())
                .map(t -> {
                    MobilityInfo info = t.getT3().get();
                    LegType legType = type == MobilityType.KICKBOARD_SHARED ? LegType.KICKBOARD : LegType.BIKE;
                    RouteType routeType = type == MobilityType.KICKBOARD_SHARED
                            ? RouteType.TRANSIT_WITH_KICKBOARD : RouteType.TRANSIT_WITH_BIKE;
                    List<Leg> legs = List.of(
                            new Leg(LegType.TRANSIT, "BUS", t.getT1(), 0, origin, switchPoint, null, null),
                            new Leg(legType, type.name(), t.getT2(), 0, switchPoint, destination, null, info)
                    );
                    return Route.of(legs, routeType);
                });
    }

    private List<Route> rank(Route base, List<Route> combined, int baseMinutes) {
        List<Route> all = new ArrayList<>(combined);
        all.add(base);
        List<Route> scored = all.stream()
                .map(r -> r.withScore(scoreCalculator.calculate(r), false))
                .sorted(Comparator.comparingDouble(Route::score).reversed())
                .limit(5).toList();
        List<Route> result = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            int saved = Math.max(baseMinutes - scored.get(i).totalMinutes(), 0);
            Route r = scored.get(i).withComparison(new Comparison(baseMinutes, saved));
            result.add(i == 0 ? r.withScore(r.score(), true) : r);
        }
        return result;
    }
}
```

**Step 4: 테스트 통과 확인**

```bash
./gradlew :application:test --tests "*.SpecificMobilityStrategyTest"
```

Expected: PASS

**Step 5: 커밋**

```bash
git add application/src/
git commit -m "feat: SpecificMobilityStrategy 구현 (기존 라스트마일 로직 이동)"
```

---

## Task 4: CandidatePointSelector — selectFirstMile() 추가 (TDD)

**Files:**
- Modify: `application/src/main/java/com/blackmamba/navigation/application/route/CandidatePointSelector.java`
- Modify: `application/src/test/java/com/blackmamba/navigation/application/route/CandidatePointSelectorTest.java`

**Step 1: 실패 테스트 작성**

기존 테스트 파일 하단에 추가:

```java
@Test
void 퍼스트마일_출발지_근처_0_30퍼센트_정류장을_반환한다() {
    // 10개 정류장 (0~30% = 앞 3개)
    // 출발지 (37.5, 126.9) 에서 5000m 이내인 정류장만
    Location origin = new Location("출발", 37.5, 126.9);
    Location nearStop = new Location("근처역", 37.505, 126.905);   // ~700m
    Location farStop  = new Location("먼역",  37.6,   126.9);      // ~11km

    TransitInfo ti = new TransitInfo("지하철", "2호선", 10, 0, 0);

    // 10개 보간 정류장 → 앞 3개(0,1,2번)가 first-mile 구간
    // nearStop 과 farStop 이 1번, 2번 위치에 대응되도록
    // start=(37.5, 126.9), end=(37.6, 126.9) → latStep=0.01
    // 0번=37.5, 1번=37.51, ..., 9번=37.59
    // 0~30%=0,1,2번: 37.5(~0m), 37.51(~1.1km), 37.52(~2.2km) → 모두 5000m 이내
    Leg leg = new Leg(LegType.TRANSIT, "지하철", 30, 10000,
            new Location("시작", 37.5, 126.9),
            new Location("끝",   37.59, 126.9),
            ti, null);

    MobilityConfig config = MobilityConfig.kickboard(); // 5000m

    List<Location> candidates = selector.selectFirstMile(origin, List.of(leg), config);

    assertThat(candidates).isNotEmpty();
    // 모든 후보가 출발지에서 5000m 이내
    candidates.forEach(c -> {
        double dist = haversineMeters(origin.lat(), origin.lng(), c.lat(), c.lng());
        assertThat(dist).isLessThanOrEqualTo(5000.0);
    });
}
```

`CandidatePointSelectorTest`에 haversineMeters 헬퍼 메서드도 추가:

```java
private double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
    double dLat = Math.toRadians(lat2 - lat1);
    double dLng = Math.toRadians(lng2 - lng1);
    double a = Math.sin(dLat/2)*Math.sin(dLat/2)
             + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
             * Math.sin(dLng/2)*Math.sin(dLng/2);
    return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
}
```

**Step 2: 실패 확인**

```bash
./gradlew :application:test --tests "*.CandidatePointSelectorTest"
```

Expected: FAIL — `selectFirstMile` 메서드 없음

**Step 3: selectFirstMile 구현**

`CandidatePointSelector`에 추가:

```java
/**
 * 퍼스트마일용: 경로의 첫 0~30% 구간 정류장 중 출발지에서 이동수단 범위 이내인 것 반환.
 */
public List<Location> selectFirstMile(Location origin, List<Leg> legs, MobilityConfig config) {
    List<Location> allStops = extractTransitStops(legs);
    if (allStops.isEmpty()) return List.of();

    int to = Math.max(1, (int) (allStops.size() * 0.3));
    List<Location> firstSegment = allStops.subList(0, Math.min(to, allStops.size()));

    return firstSegment.stream()
            .filter(stop -> distanceMeters(
                    origin.lat(), origin.lng(),
                    stop.lat(), stop.lng()) <= config.maxRangeMeters())
            .toList();
}
```

**Step 4: 테스트 통과 확인**

```bash
./gradlew :application:test --tests "*.CandidatePointSelectorTest"
```

Expected: 4 tests PASS

**Step 5: 커밋**

```bash
git add application/src/
git commit -m "feat: CandidatePointSelector.selectFirstMile() 추가 (퍼스트마일 후보 추출)"
```

---

## Task 5: OptimalSearchStrategy — 패턴 A·B·C·E 구현 (TDD)

**Files:**
- Create: `application/src/test/java/com/blackmamba/navigation/application/route/strategy/OptimalSearchStrategyTest.java`
- Create: `application/src/main/java/com/blackmamba/navigation/application/route/strategy/OptimalSearchStrategy.java`

**Step 1: 테스트 작성 (RED)**

```java
// application/src/test/java/com/blackmamba/navigation/application/route/strategy/OptimalSearchStrategyTest.java
package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.*;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptimalSearchStrategyTest {

    @Mock TransitRoutePort transitRoutePort;
    @Mock MobilityTimePort mobilityTimePort;
    @Mock MobilityAvailabilityPort mobilityAvailabilityPort;
    @Mock CandidatePointSelector candidatePointSelector;
    @Mock RouteScoreCalculator scoreCalculator;

    OptimalSearchStrategy strategy;
    Location origin      = new Location("서울역", 37.5547, 126.9706);
    Location destination = new Location("강남역", 37.4979, 127.0276);
    Location candidate   = new Location("중간역", 37.52, 127.0);
    Leg baseLeg;

    @BeforeEach
    void setUp() {
        strategy = new OptimalSearchStrategy(
                transitRoutePort, mobilityTimePort,
                mobilityAvailabilityPort, candidatePointSelector, scoreCalculator);
        baseLeg = new Leg(LegType.TRANSIT, "BUS", 40, 10000, origin, destination, null, null);
        when(transitRoutePort.getTransitRoute(any(), any()))
                .thenReturn(Mono.just(List.of(baseLeg)));
        when(scoreCalculator.calculate(any())).thenReturn(0.5);
    }

    @Test
    void 대중교통_기본_경로는_항상_포함된다() {
        // 이동수단 없는 상황: 후보 없음
        when(candidatePointSelector.select(any(), any())).thenReturn(List.of());
        when(candidatePointSelector.selectFirstMile(any(), any(), any())).thenReturn(List.of());
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.stream().anyMatch(r -> r.type() == RouteType.TRANSIT_ONLY)).isTrue();
    }

    @Test
    void 이동수단만_경로가_거리_범위_내이면_패턴E_포함된다() {
        // 강남→서울역 직선 약 7km → 킥보드(5km) 범위 초과, 따릉이(10km) 범위 이내
        when(candidatePointSelector.select(any(), any())).thenReturn(List.of());
        when(candidatePointSelector.selectFirstMile(any(), any(), any())).thenReturn(List.of());
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.DDAREUNGI, "따릉이",
                                null, 100, "서울역", 37.5547, 126.9706, 5, 0))));
        when(mobilityTimePort.getMobilityTimeMinutes(any(), any(), any()))
                .thenReturn(Mono.just(30));
        when(scoreCalculator.calculate(any())).thenReturn(0.6, 0.5);

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes.stream().anyMatch(r -> r.type() == RouteType.MOBILITY_ONLY)).isTrue();
    }

    @Test
    void 최적_경로_1위에는_추천_표시가_붙는다() {
        when(candidatePointSelector.select(any(), any())).thenReturn(List.of(candidate));
        when(candidatePointSelector.selectFirstMile(any(), any(), any())).thenReturn(List.of());
        when(transitRoutePort.getTransitTimeMinutes(any(), any())).thenReturn(Mono.just(20));
        when(mobilityTimePort.getMobilityTimeMinutes(any(), any(), any())).thenReturn(Mono.just(8));
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.KICKBOARD_SHARED, "씽씽",
                                "K001", 80, null, 37.52, 127.0, 1, 100))));
        when(scoreCalculator.calculate(any())).thenReturn(0.9, 0.8, 0.7, 0.5);

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes.get(0).recommended()).isTrue();
        assertThat(routes.stream().filter(Route::recommended)).hasSize(1);
    }
}
```

**Step 2: 실패 확인**

```bash
./gradlew :application:test --tests "*.OptimalSearchStrategyTest"
```

Expected: FAIL — `OptimalSearchStrategy` 없음

**Step 3: OptimalSearchStrategy 구현**

```java
// application/src/main/java/com/blackmamba/navigation/application/route/strategy/OptimalSearchStrategy.java
package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.*;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 모든수단 최적탐색 전략.
 *
 * 3가지 수단(따릉이/킥보드/개인) × 4가지 패턴(B,C,D,E) + 패턴A(대중교통만) = 최대 13개 후보 병렬 탐색.
 *
 * 패턴 B: 퍼스트마일  — 이동수단(출발→정류장) + 대중교통(정류장→목적지)
 * 패턴 C: 라스트마일  — 대중교통(출발→환승점) + 이동수단(환승점→목적지)
 * 패턴 D: 퍼스트+라스트 — 이동수단 + 대중교통(중간) + 이동수단
 * 패턴 E: 이동수단만  — haversine 거리 < 수단 최대범위일 때
 */
public class OptimalSearchStrategy implements RouteSearchStrategy {

    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final List<MobilityType> ALL_TYPES =
            List.of(MobilityType.DDAREUNGI, MobilityType.KICKBOARD_SHARED, MobilityType.PERSONAL);

    private final TransitRoutePort transitRoutePort;
    private final MobilityTimePort mobilityTimePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final CandidatePointSelector candidatePointSelector;
    private final RouteScoreCalculator scoreCalculator;

    public OptimalSearchStrategy(TransitRoutePort transitRoutePort,
                                  MobilityTimePort mobilityTimePort,
                                  MobilityAvailabilityPort mobilityAvailabilityPort,
                                  CandidatePointSelector candidatePointSelector,
                                  RouteScoreCalculator scoreCalculator) {
        this.transitRoutePort = transitRoutePort;
        this.mobilityTimePort = mobilityTimePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.candidatePointSelector = candidatePointSelector;
        this.scoreCalculator = scoreCalculator;
    }

    @Override
    public Mono<List<Route>> search(Location origin, Location destination) {
        return transitRoutePort.getTransitRoute(origin, destination)
                .flatMap(baseLegs -> {
                    Route baseRoute = Route.of(baseLegs, RouteType.TRANSIT_ONLY);
                    int baseMinutes = baseRoute.totalMinutes();

                    Flux<Route> allPatterns = Flux.fromIterable(ALL_TYPES)
                            .flatMap(type -> {
                                MobilityConfig config = configFor(type);
                                return Flux.merge(
                                        patternB(origin, destination, baseLegs, type, config),
                                        patternC(origin, destination, baseLegs, type, config),
                                        patternD(origin, destination, baseLegs, type, config),
                                        patternE(origin, destination, type, config)
                                );
                            });

                    return allPatterns
                            .mergeWith(Mono.just(baseRoute))
                            .collectList()
                            .map(candidates -> rank(candidates, baseMinutes));
                });
    }

    // 패턴 B: 이동수단으로 첫 정류장까지 → 대중교통으로 목적지
    private Flux<Route> patternB(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        List<Location> firstMile = candidatePointSelector.selectFirstMile(origin, baseLegs, config);
        return Flux.fromIterable(firstMile)
                .flatMap(transitStart ->
                        mobilityAvailabilityPort.findNearbyMobility(origin.lat(), origin.lng(), type)
                                .filter(Optional::isPresent)
                                .flatMap(avail -> {
                                    MobilityInfo info = avail.get();
                                    Mono<Integer> mobTime  = mobilityTimePort.getMobilityTimeMinutes(origin, transitStart, type);
                                    Mono<Integer> tranTime = transitRoutePort.getTransitTimeMinutes(transitStart, destination);
                                    return Mono.zip(mobTime, tranTime)
                                            .map(t -> buildRoute(
                                                    List.of(
                                                            mobilityLeg(type, t.getT1(), origin, transitStart, info),
                                                            transitLeg(t.getT2(), transitStart, destination)
                                                    ), RouteType.MOBILITY_FIRST_TRANSIT));
                                })
                );
    }

    // 패턴 C: 대중교통으로 환승점까지 → 이동수단으로 목적지 (기존 알고리즘과 동일)
    private Flux<Route> patternC(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        List<Location> lastMile = candidatePointSelector.select(baseLegs, config);
        return Flux.fromIterable(lastMile)
                .flatMap(switchPoint ->
                        mobilityAvailabilityPort.findNearbyMobility(switchPoint.lat(), switchPoint.lng(), type)
                                .filter(Optional::isPresent)
                                .flatMap(avail -> {
                                    MobilityInfo info = avail.get();
                                    Mono<Integer> tranTime = transitRoutePort.getTransitTimeMinutes(origin, switchPoint);
                                    Mono<Integer> mobTime  = mobilityTimePort.getMobilityTimeMinutes(switchPoint, destination, type);
                                    return Mono.zip(tranTime, mobTime)
                                            .map(t -> buildRoute(
                                                    List.of(
                                                            transitLeg(t.getT1(), origin, switchPoint),
                                                            mobilityLeg(type, t.getT2(), switchPoint, destination, info)
                                                    ), routeTypeFor(type)));
                                })
                );
    }

    // 패턴 D: 이동수단→정류장 + 대중교통(중간) + 이동수단→목적지
    private Flux<Route> patternD(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        List<Location> firstMile = candidatePointSelector.selectFirstMile(origin, baseLegs, config);
        List<Location> lastMile  = candidatePointSelector.select(baseLegs, config);
        if (firstMile.isEmpty() || lastMile.isEmpty()) return Flux.empty();

        Location transitStart = firstMile.get(0);
        Location transitEnd   = lastMile.get(lastMile.size() / 2);

        return mobilityAvailabilityPort.findNearbyMobility(origin.lat(), origin.lng(), type)
                .filter(Optional::isPresent)
                .flatMapMany(avail -> {
                    MobilityInfo info = avail.get();
                    Mono<Integer> mob1  = mobilityTimePort.getMobilityTimeMinutes(origin, transitStart, type);
                    Mono<Integer> tran  = transitRoutePort.getTransitTimeMinutes(transitStart, transitEnd);
                    Mono<Integer> mob2  = mobilityTimePort.getMobilityTimeMinutes(transitEnd, destination, type);
                    return Mono.zip(mob1, tran, mob2)
                            .map(t -> buildRoute(
                                    List.of(
                                            mobilityLeg(type, t.getT1(), origin, transitStart, info),
                                            transitLeg(t.getT2(), transitStart, transitEnd),
                                            mobilityLeg(type, t.getT3(), transitEnd, destination, info)
                                    ), RouteType.MOBILITY_TRANSIT_MOBILITY))
                            .flux();
                });
    }

    // 패턴 E: 이동수단만 (직선거리 < 수단 최대범위)
    private Flux<Route> patternE(Location origin, Location destination,
                                  MobilityType type, MobilityConfig config) {
        double dist = haversineMeters(origin.lat(), origin.lng(), destination.lat(), destination.lng());
        if (dist > config.maxRangeMeters()) return Flux.empty();

        return mobilityAvailabilityPort.findNearbyMobility(origin.lat(), origin.lng(), type)
                .filter(Optional::isPresent)
                .flatMapMany(avail -> {
                    MobilityInfo info = avail.get();
                    return mobilityTimePort.getMobilityTimeMinutes(origin, destination, type)
                            .map(t -> buildRoute(
                                    List.of(mobilityLeg(type, t, origin, destination, info)),
                                    RouteType.MOBILITY_ONLY))
                            .flux();
                });
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private Route buildRoute(List<Leg> legs, RouteType type) {
        return Route.of(legs, type);
    }

    private Leg transitLeg(int minutes, Location from, Location to) {
        return new Leg(LegType.TRANSIT, "대중교통", minutes, 0, from, to, null, null);
    }

    private Leg mobilityLeg(MobilityType type, int minutes, Location from, Location to, MobilityInfo info) {
        LegType legType = type == MobilityType.KICKBOARD_SHARED ? LegType.KICKBOARD : LegType.BIKE;
        return new Leg(legType, type.name(), minutes, 0, from, to, null, info);
    }

    private MobilityConfig configFor(MobilityType type) {
        return type == MobilityType.KICKBOARD_SHARED ? MobilityConfig.kickboard() : MobilityConfig.bike();
    }

    private RouteType routeTypeFor(MobilityType type) {
        return type == MobilityType.KICKBOARD_SHARED
                ? RouteType.TRANSIT_WITH_KICKBOARD : RouteType.TRANSIT_WITH_BIKE;
    }

    private List<Route> rank(List<Route> candidates, int baseMinutes) {
        List<Route> scored = candidates.stream()
                .map(r -> r.withScore(scoreCalculator.calculate(r), false))
                .sorted(Comparator.comparingDouble(Route::score).reversed())
                .limit(5).toList();
        List<Route> result = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            int saved = Math.max(baseMinutes - scored.get(i).totalMinutes(), 0);
            Route r = scored.get(i).withComparison(new Comparison(baseMinutes, saved));
            result.add(i == 0 ? r.withScore(r.score(), true) : r);
        }
        return result;
    }

    private double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng/2)*Math.sin(dLng/2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }
}
```

**Step 4: 테스트 통과 확인**

```bash
./gradlew :application:test --tests "*.OptimalSearchStrategyTest"
```

Expected: 3 tests PASS

**Step 5: 커밋**

```bash
git add application/src/
git commit -m "feat: OptimalSearchStrategy 구현 (패턴 A·B·C·D·E 병렬 탐색)"
```

---

## Task 6: RouteOptimizationService 리팩터링

기존 로직을 Strategy들에 이미 이동했으므로, `RouteOptimizationService`는 전략 선택자 역할만 수행.

**Files:**
- Modify: `application/src/main/java/com/blackmamba/navigation/application/route/RouteOptimizationService.java`
- Modify: `application/src/test/java/com/blackmamba/navigation/application/route/RouteOptimizationServiceTest.java`

**Step 1: 테스트 수정 (기존 테스트 → searchMode 기반으로)**

```java
// RouteOptimizationServiceTest.java 전체 교체
@ExtendWith(MockitoExtension.class)
class RouteOptimizationServiceTest {

    @Mock TransitRoutePort transitRoutePort;
    @Mock MobilityTimePort mobilityTimePort;
    @Mock MobilityAvailabilityPort mobilityAvailabilityPort;
    @Mock CandidatePointSelector candidatePointSelector;
    @Mock RouteScoreCalculator scoreCalculator;

    @InjectMocks RouteOptimizationService service;

    Location origin = new Location("서울역", 37.5547, 126.9706);
    Location dest   = new Location("강남역", 37.4979, 127.0276);

    @Test
    void SPECIFIC_모드_이동수단_없으면_대중교통만_반환한다() {
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 45, 10000, origin, dest, null, null);
        when(transitRoutePort.getTransitRoute(any(), any())).thenReturn(Mono.just(List.of(leg)));
        when(scoreCalculator.calculate(any())).thenReturn(0.5);

        List<Route> routes = service.findRoutes(origin, dest, List.of(), SearchMode.SPECIFIC).block();

        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).type()).isEqualTo(RouteType.TRANSIT_ONLY);
    }

    @Test
    void OPTIMAL_모드는_대중교통_경로를_항상_포함한다() {
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 40, 10000, origin, dest, null, null);
        when(transitRoutePort.getTransitRoute(any(), any())).thenReturn(Mono.just(List.of(leg)));
        when(candidatePointSelector.select(any(), any())).thenReturn(List.of());
        when(candidatePointSelector.selectFirstMile(any(), any(), any())).thenReturn(List.of());
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        when(scoreCalculator.calculate(any())).thenReturn(0.5);

        List<Route> routes = service.findRoutes(origin, dest, List.of(), SearchMode.OPTIMAL).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.stream().anyMatch(r -> r.type() == RouteType.TRANSIT_ONLY)).isTrue();
    }
}
```

**Step 2: RouteOptimizationService 리팩터링**

```java
// RouteOptimizationService.java
@Service
public class RouteOptimizationService {

    private final TransitRoutePort transitRoutePort;
    private final MobilityTimePort mobilityTimePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final CandidatePointSelector candidatePointSelector;
    private final RouteScoreCalculator scoreCalculator;

    public RouteOptimizationService(TransitRoutePort transitRoutePort,
                                     MobilityTimePort mobilityTimePort,
                                     MobilityAvailabilityPort mobilityAvailabilityPort,
                                     CandidatePointSelector candidatePointSelector,
                                     RouteScoreCalculator scoreCalculator) {
        this.transitRoutePort = transitRoutePort;
        this.mobilityTimePort = mobilityTimePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.candidatePointSelector = candidatePointSelector;
        this.scoreCalculator = scoreCalculator;
    }

    public Mono<List<Route>> findRoutes(Location origin, Location destination,
                                         List<MobilityType> mobilityTypes,
                                         SearchMode searchMode) {
        RouteSearchStrategy strategy = switch (searchMode) {
            case OPTIMAL -> new OptimalSearchStrategy(
                    transitRoutePort, mobilityTimePort,
                    mobilityAvailabilityPort, candidatePointSelector, scoreCalculator);
            case SPECIFIC -> new SpecificMobilityStrategy(
                    mobilityTypes, transitRoutePort, mobilityTimePort,
                    mobilityAvailabilityPort, candidatePointSelector, scoreCalculator);
        };
        return strategy.search(origin, destination);
    }
}
```

> ⚠️ `RouteOptimizationService`의 기존 `findRoutes(origin, dest, List<MobilityType>)` 시그니처가 변경됨.
> `RouteController`도 함께 수정 필요 (Task 7).

**Step 3: 테스트 통과 확인**

```bash
./gradlew :application:test
```

Expected: 전체 PASS (RouteOptimizationServiceTest 2개 포함)

**Step 4: 커밋**

```bash
git add application/src/
git commit -m "refactor: RouteOptimizationService를 Strategy 선택자로 슬림화"
```

---

## Task 7: RouteController — searchMode 파라미터 추가 (TDD)

**Files:**
- Modify: `api/src/main/java/com/blackmamba/navigation/api/route/RouteController.java`
- Modify: `api/src/test/java/com/blackmamba/navigation/api/route/RouteControllerTest.java`

**Step 1: 테스트 수정**

기존 테스트에 searchMode 케이스 추가:

```java
@Test
void searchMode_OPTIMAL_파라미터로_경로를_탐색한다() throws Exception {
    when(routeOptimizationService.findRoutes(any(), any(), any(), eq(SearchMode.OPTIMAL)))
            .thenReturn(Mono.just(List.of(
                    new Route("rt_opt", RouteType.MOBILITY_ONLY, 20, 0, 0.9, true, List.of(), null)
            )));

    mockMvc.perform(get("/api/routes")
                    .param("originLat", "37.5547")
                    .param("originLng", "126.9706")
                    .param("destLat",   "37.4979")
                    .param("destLng",   "127.0276")
                    .param("searchMode", "OPTIMAL"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routes[0].routeId").value("rt_opt"));
}
```

**Step 2: RouteController 수정**

```java
@GetMapping
public ResponseEntity<Map<String, Object>> searchRoutes(
        @RequestParam double originLat,
        @RequestParam double originLng,
        @RequestParam double destLat,
        @RequestParam double destLng,
        @RequestParam(defaultValue = "") List<String> mobility,
        @RequestParam(defaultValue = "SPECIFIC") SearchMode searchMode
) {
    Location origin      = new Location("출발지", originLat, originLng);
    Location destination = new Location("목적지", destLat, destLng);

    List<MobilityType> mobilityTypes = mobility.stream()
            .filter(m -> !m.isBlank())
            .map(MobilityType::valueOf)
            .toList();

    List<Route> routes = routeOptimizationService
            .findRoutes(origin, destination, mobilityTypes, searchMode)
            .block();

    return ResponseEntity.ok(Map.of("routes", routes));
}
```

**Step 3: 전체 테스트 통과 확인**

```bash
./gradlew test
```

Expected: 전체 PASS

**Step 4: 커밋**

```bash
git add api/src/
git commit -m "feat: RouteController searchMode 파라미터 추가 (SPECIFIC|OPTIMAL)"
```

---

## Task 8: 프론트엔드 — 🔍 최적 탐색 버튼 + searchMode 연동

**Files:**
- Modify: `frontend/src/components/search/MobilitySelector.jsx`
- Modify: `frontend/src/pages/MainPage.jsx`
- Modify: `frontend/src/api/routeApi.js`

**Step 1: MobilitySelector.jsx 수정**

```jsx
// frontend/src/components/search/MobilitySelector.jsx
const MOBILITY_OPTIONS = [
  { id: 'DDAREUNGI',      label: '🚲 따릉이' },
  { id: 'KICKBOARD_SHARED', label: '🛴 킥보드' },
  { id: 'PERSONAL',       label: '🚴 개인자전거' },
]

const OPTIMAL_ID = 'OPTIMAL'

export default function MobilitySelector({ selected, onChange, searchMode, onSearchModeChange }) {
  const isOptimal = searchMode === OPTIMAL_ID

  const toggleOptimal = () => {
    if (isOptimal) {
      onSearchModeChange('SPECIFIC')
    } else {
      onChange([])               // 기존 선택 해제
      onSearchModeChange(OPTIMAL_ID)
    }
  }

  const toggle = (id) => {
    onSearchModeChange('SPECIFIC')  // 일반 버튼 누르면 SPECIFIC
    onChange(selected.includes(id)
      ? selected.filter(s => s !== id)
      : [...selected, id])
  }

  return (
    <div className="flex gap-2 flex-wrap">
      {MOBILITY_OPTIONS.map(opt => (
        <button
          key={opt.id}
          onClick={() => toggle(opt.id)}
          disabled={isOptimal}
          className={`px-3 py-1 rounded-full border text-sm transition
            ${isOptimal ? 'opacity-40 cursor-not-allowed bg-white text-gray-400 border-gray-200'
              : selected.includes(opt.id)
                ? 'bg-blue-500 text-white border-blue-500'
                : 'bg-white text-gray-600 border-gray-300'}`}
        >
          {opt.label}
        </button>
      ))}
      {/* 최적 탐색 버튼 */}
      <button
        onClick={toggleOptimal}
        className={`px-3 py-1 rounded-full border text-sm transition
          ${isOptimal
            ? 'bg-purple-500 text-white border-purple-500'
            : 'bg-white text-purple-600 border-purple-300'}`}
      >
        🔍 최적 탐색
      </button>
    </div>
  )
}
```

**Step 2: MainPage.jsx 수정**

```jsx
// searchMode 상태 추가, MobilitySelector props 업데이트, URL 파라미터 전달
const [searchMode, setSearchMode] = useState('SPECIFIC')

const handleSearch = () => {
  navigate(
    `/routes?origin=${origin}&dest=${destination}` +
    `&mobility=${mobility.join(',')}&searchMode=${searchMode}`
  )
}

// JSX 안 MobilitySelector 부분:
<MobilitySelector
  selected={mobility}
  onChange={setMobility}
  searchMode={searchMode}
  onSearchModeChange={setSearchMode}
/>
```

**Step 3: routeApi.js 수정**

```js
export const searchRoutes = async ({ originLat, originLng, destLat, destLng, mobility, searchMode = 'SPECIFIC' }) => {
  const { data } = await axios.get(`${BASE_URL}/routes`, {
    params: {
      originLat, originLng, destLat, destLng,
      mobility: mobility.join(','),
      searchMode
    }
  })
  return data.routes
}
```

**Step 4: RouteListPage.jsx — searchMode URL 파라미터 읽기**

`RouteListPage.jsx`의 `useEffect` 안에 추가:

```js
const searchMode = searchParams.get('searchMode') || 'SPECIFIC'

searchRoutes({
  originLat: origin.lat, originLng: origin.lng,
  destLat: dest.lat, destLng: dest.lng,
  mobility,
  searchMode   // 추가
})
```

**Step 5: 프론트엔드 빌드 확인**

```bash
cd /Users/sjw/Desktop/black-mamba/frontend && npm run build
```

Expected: `✓ built in ~800ms`

**Step 6: 전체 백엔드 테스트 확인**

```bash
cd /Users/sjw/Desktop/black-mamba && ./gradlew test
```

Expected: 전체 PASS

**Step 7: 최종 커밋**

```bash
cd /Users/sjw/Desktop/black-mamba
git add frontend/ api/ application/ domain/
git commit -m "feat: 모든수단 최적탐색 완성 (🔍 버튼 + OPTIMAL 알고리즘)"
```

---

## 완료 기준

- [ ] `./gradlew test` — 전체 GREEN
- [ ] `npm run build` — 빌드 성공
- [ ] `GET /api/routes?searchMode=OPTIMAL` — 5가지 패턴 탐색 후 상위 5개 반환
- [ ] `GET /api/routes?searchMode=SPECIFIC&mobility=KICKBOARD_SHARED` — 기존 동작 유지
- [ ] UI에서 🔍 최적 탐색 버튼 → 보라색, 다른 버튼 비활성화
