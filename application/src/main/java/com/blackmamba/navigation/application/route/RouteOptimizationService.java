package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.application.route.strategy.OptimalSearchStrategy;
import com.blackmamba.navigation.application.route.strategy.SpecificMobilityStrategy;
import com.blackmamba.navigation.application.route.strategy.RouteSearchStrategy;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
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
    private final RouteScoreCalculator scoreCalculator;
    private final RouteInsightFactory routeInsightFactory;

    public RouteOptimizationService(TransitRoutePort transitRoutePort,
                                     MobilityTimePort mobilityTimePort,
                                     MobilityAvailabilityPort mobilityAvailabilityPort,
                                     HubSelector hubSelector,
                                     RouteScoreCalculator scoreCalculator,
                                     RouteInsightFactory routeInsightFactory) {
        this.transitRoutePort = transitRoutePort;
        this.mobilityTimePort = mobilityTimePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.hubSelector = hubSelector;
        this.scoreCalculator = scoreCalculator;
        this.routeInsightFactory = routeInsightFactory;
    }

    public Mono<List<Route>> findRoutes(Location origin, Location destination,
                                         List<MobilityType> mobilityTypes,
                                         SearchMode searchMode) {
        RouteSearchStrategy strategy = switch (searchMode) {
            case OPTIMAL -> new OptimalSearchStrategy(
                    transitRoutePort, mobilityTimePort,
                    mobilityAvailabilityPort, hubSelector, scoreCalculator, routeInsightFactory);
            case SPECIFIC -> new SpecificMobilityStrategy(
                    mobilityTypes, transitRoutePort, mobilityTimePort,
                    mobilityAvailabilityPort, hubSelector, scoreCalculator, routeInsightFactory);
        };
        return strategy.search(origin, destination);
    }
}
