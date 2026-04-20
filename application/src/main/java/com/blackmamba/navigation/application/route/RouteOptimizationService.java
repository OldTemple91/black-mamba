package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.application.route.strategy.OptimalSearchStrategy;
import com.blackmamba.navigation.application.route.strategy.SpecificMobilityStrategy;
import com.blackmamba.navigation.application.route.strategy.RouteSearchStrategy;
import com.blackmamba.navigation.domain.location.Location;
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

    private final TransitRoutePort transitRoutePort;
    private final MobilityTimePort mobilityTimePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final HubSelector hubSelector;
    private final RouteEvaluator routeEvaluator;
    private final CarReferenceCalculator carReferenceCalculator;
    private final AccessibilityPostProcessor accessibilityPostProcessor;

    public RouteOptimizationService(TransitRoutePort transitRoutePort,
                                     MobilityTimePort mobilityTimePort,
                                     MobilityAvailabilityPort mobilityAvailabilityPort,
                                     HubSelector hubSelector,
                                     RouteEvaluator routeEvaluator,
                                     CarReferenceCalculator carReferenceCalculator,
                                     AccessibilityPostProcessor accessibilityPostProcessor) {
        this.transitRoutePort = transitRoutePort;
        this.mobilityTimePort = mobilityTimePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.hubSelector = hubSelector;
        this.routeEvaluator = routeEvaluator;
        this.carReferenceCalculator = carReferenceCalculator;
        this.accessibilityPostProcessor = accessibilityPostProcessor;
    }

    public Mono<List<Route>> findRoutes(Location origin, Location destination,
                                         List<MobilityType> mobilityTypes,
                                         SearchMode searchMode) {
        return findRoutes(origin, destination, mobilityTypes, searchMode,
                RecommendationPreference.RELIABILITY, AccessibilityContext.DEFAULT);
    }

    public Mono<List<Route>> findRoutes(Location origin, Location destination,
                                        List<MobilityType> mobilityTypes,
                                        SearchMode searchMode,
                                        RecommendationPreference recommendationPreference) {
        return findRoutes(origin, destination, mobilityTypes, searchMode,
                recommendationPreference, AccessibilityContext.DEFAULT);
    }

    @Observed(name = "navigation.route.search",
            contextualName = "경로 탐색",
            lowCardinalityKeyValues = {"component", "RouteOptimizationService"})
    public Mono<List<Route>> findRoutes(Location origin, Location destination,
                                        List<MobilityType> mobilityTypes,
                                        SearchMode searchMode,
                                        RecommendationPreference recommendationPreference,
                                        AccessibilityContext accessibilityContext) {
        RouteSearchStrategy strategy = switch (searchMode) {
            case OPTIMAL -> new OptimalSearchStrategy(
                    transitRoutePort, mobilityTimePort,
                    mobilityAvailabilityPort, hubSelector, routeEvaluator, recommendationPreference);
            case SPECIFIC -> new SpecificMobilityStrategy(
                    mobilityTypes, transitRoutePort, mobilityTimePort,
                    mobilityAvailabilityPort, hubSelector, routeEvaluator, recommendationPreference);
        };
        return strategy.search(origin, destination)
                .map(routes -> accessibilityPostProcessor.apply(routes, accessibilityContext))
                .map(routes -> attachCarComparison(routes, origin, destination));
    }

    /**
     * F-1: 각 경로에 "자가용 대비 비교" 정보를 첨부한다.
     * 전략이 생성한 모든 경로에 일괄 적용해 프론트엔드가 "자가용 대체" 설득 UI를 렌더링할 수 있게 한다.
     */
    private List<Route> attachCarComparison(List<Route> routes, Location origin, Location destination) {
        return routes.stream()
                .map(route -> route.withCarComparison(
                        carReferenceCalculator.compareWithRoute(route, origin, destination)))
                .toList();
    }
}
