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
