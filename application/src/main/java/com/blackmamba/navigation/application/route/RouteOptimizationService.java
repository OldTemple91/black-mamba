package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.application.route.strategy.OptimalSearchStrategy;
import com.blackmamba.navigation.application.route.strategy.SpecificMobilityStrategy;
import com.blackmamba.navigation.application.route.strategy.RouteSearchStrategy;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.route.*;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 경로 탐색 컨텍스트.
 * searchMode에 따라 적절한 RouteSearchStrategy를 선택하고 위임.
 */
@Service
public class RouteOptimizationService {

    private final CarReferenceCalculator carReferenceCalculator;
    private final CarbonFootprintCalculator carbonFootprintCalculator;
    private final AccessibilityPostProcessor accessibilityPostProcessor;
    private final WeatherAwareRouteAdjuster weatherAwareRouteAdjuster;
    private final RouteHistoryRecorder routeHistoryRecorder;

    // 전략은 stateless — 요청별 상태(preference, mobilityTypes)는 search() 파라미터로 전달.
    // 매 요청 객체 생성을 피해 싱글톤으로 보유 (GC 압력 제거).
    private final RouteSearchStrategy optimalStrategy;
    private final RouteSearchStrategy specificStrategy;

    public RouteOptimizationService(TransitRoutePort transitRoutePort,
                                     MobilityTimePort mobilityTimePort,
                                     MobilityAvailabilityPort mobilityAvailabilityPort,
                                     HubSelector hubSelector,
                                     RouteEvaluator routeEvaluator,
                                     CarReferenceCalculator carReferenceCalculator,
                                     CarbonFootprintCalculator carbonFootprintCalculator,
                                     AccessibilityPostProcessor accessibilityPostProcessor,
                                     WeatherAwareRouteAdjuster weatherAwareRouteAdjuster,
                                     RouteHistoryRecorder routeHistoryRecorder) {
        this.carReferenceCalculator = carReferenceCalculator;
        this.carbonFootprintCalculator = carbonFootprintCalculator;
        this.accessibilityPostProcessor = accessibilityPostProcessor;
        this.weatherAwareRouteAdjuster = weatherAwareRouteAdjuster;
        this.routeHistoryRecorder = routeHistoryRecorder;
        this.optimalStrategy = new OptimalSearchStrategy(
                transitRoutePort, mobilityTimePort, mobilityAvailabilityPort, hubSelector, routeEvaluator);
        this.specificStrategy = new SpecificMobilityStrategy(
                transitRoutePort, mobilityTimePort, mobilityAvailabilityPort, hubSelector, routeEvaluator);
    }

    public Mono<List<Route>> findRoutes(Location origin, Location destination,
                                         List<MobilityType> mobilityTypes,
                                         SearchMode searchMode) {
        return findRoutes(origin, destination, mobilityTypes, searchMode,
                RecommendationPreference.RELIABILITY, AccessibilityContext.DEFAULT, WeatherContext.DEFAULT);
    }

    public Mono<List<Route>> findRoutes(Location origin, Location destination,
                                        List<MobilityType> mobilityTypes,
                                        SearchMode searchMode,
                                        RecommendationPreference recommendationPreference) {
        return findRoutes(origin, destination, mobilityTypes, searchMode,
                recommendationPreference, AccessibilityContext.DEFAULT, WeatherContext.DEFAULT);
    }

    public Mono<List<Route>> findRoutes(Location origin, Location destination,
                                        List<MobilityType> mobilityTypes,
                                        SearchMode searchMode,
                                        RecommendationPreference recommendationPreference,
                                        AccessibilityContext accessibilityContext) {
        return findRoutes(origin, destination, mobilityTypes, searchMode,
                recommendationPreference, accessibilityContext, WeatherContext.DEFAULT);
    }

    @Observed(name = "navigation.route.search",
            contextualName = "경로 탐색",
            lowCardinalityKeyValues = {"component", "RouteOptimizationService"})
    public Mono<List<Route>> findRoutes(Location origin, Location destination,
                                        List<MobilityType> mobilityTypes,
                                        SearchMode searchMode,
                                        RecommendationPreference recommendationPreference,
                                        AccessibilityContext accessibilityContext,
                                        WeatherContext weatherContext) {
        RouteSearchStrategy strategy = switch (searchMode) {
            case OPTIMAL -> optimalStrategy;
            case SPECIFIC -> specificStrategy;
        };
        String preferenceName = recommendationPreference == null
                ? "RELIABILITY" : recommendationPreference.name();

        return strategy.search(origin, destination, mobilityTypes, recommendationPreference)
                .map(routes -> accessibilityPostProcessor.apply(routes, accessibilityContext))
                .map(routes -> weatherAwareRouteAdjuster.apply(routes, weatherContext))   // A-4
                .map(routes -> attachCarbonAndComparison(routes, origin, destination))
                // RAG Phase 2: 추천 경로를 벡터 DB 에 비동기 저장 (fire-and-forget, 본 요청에 영향 없음)
                .doOnNext(routes -> routeHistoryRecorder.recordAsync(routes, origin, destination, preferenceName));
    }

    /**
     * 각 경로에 C-2 Carbon + F-1 자가용 비교 정보를 첨부한다.
     * 순서가 중요: Carbon 을 먼저 계산해야 CarReferenceCalculator 의 narrative 가
     * 정확한 CO₂ 값을 사용할 수 있다.
     */
    private List<Route> attachCarbonAndComparison(List<Route> routes, Location origin, Location destination) {
        return routes.stream()
                .map(route -> route.withCarbon(computeCarbon(route, origin, destination)))
                .map(route -> route.withCarComparison(
                        carReferenceCalculator.compareWithRoute(route, origin, destination)))
                .toList();
    }

    /**
     * C-2: 경로별 탄소 배출량 + 자가용 대비 감축량 계산.
     */
    private CarbonSummary computeCarbon(Route route, Location origin, Location destination) {
        double grams = carbonFootprintCalculator.forRoute(route);
        int totalMeters = route.legs().stream().mapToInt(Leg::distanceMeters).sum();
        double gramsPerKm = totalMeters > 0 ? grams / (totalMeters / 1_000.0) : 0.0;

        double carKm = haversineKm(origin, destination) * 1.3;  // 자가용 우회계수와 동일
        double carGrams = carbonFootprintCalculator.forCarDistance(carKm);
        double saved = Math.max(0, carGrams - grams);
        carbonFootprintCalculator.recordSaved(saved);

        return new CarbonSummary(
                Math.round(grams * 10.0) / 10.0,
                Math.round(gramsPerKm * 10.0) / 10.0,
                carbonFootprintCalculator.isEcoRoute(route),
                Math.round(saved * 10.0) / 10.0
        );
    }

    private static double haversineKm(Location a, Location b) {
        return GeoDistance.kilometers(a, b);
    }
}
