package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.accessibility.AccessibilityPostProcessor;
import com.blackmamba.navigation.application.route.accessibility.AccessibilityStationRegistry;
import com.blackmamba.navigation.application.route.history.RouteHistoryRecorder;
import com.blackmamba.navigation.application.route.hub.HubSelector;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.application.route.weather.WeatherAwareRouteAdjuster;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import com.blackmamba.navigation.domain.route.comparison.CarReferenceCalculator;
import com.blackmamba.navigation.domain.route.comparison.CarbonFootprintCalculator;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.route.evaluation.RouteInsightFactory;
import com.blackmamba.navigation.domain.route.evaluation.RouteScoreCalculator;
import com.blackmamba.navigation.application.route.port.TransitBudgetPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouteOptimizationServiceTest {

    @Mock TransitRoutePort transitRoutePort;
    @Mock MobilityTimePort mobilityTimePort;
    @Mock MobilityAvailabilityPort mobilityAvailabilityPort;
    @Mock HubSelector hubSelector;
    @Mock RouteEvaluator routeEvaluator;
    // RAG Phase 2: Recorder 는 비동기 fire-and-forget이라 mock 으로 주입 (호출 검증 불필요)
    @Mock RouteHistoryRecorder routeHistoryRecorder;

    // 후처리 파이프라인은 순수 계산 컴포넌트들의 조합 → 실제 인스턴스
    private final CarbonFootprintCalculator carbonFootprintCalculator = new CarbonFootprintCalculator();
    private final RouteEnrichmentPipeline enrichmentPipeline = new RouteEnrichmentPipeline(
            new RouteEvaluator(new RouteScoreCalculator(), new RouteInsightFactory()),
            new AccessibilityPostProcessor(new AccessibilityStationRegistry()),
            new WeatherAwareRouteAdjuster(),
            carbonFootprintCalculator,
            new CarReferenceCalculator(carbonFootprintCalculator),
            new CarbonMetrics(new SimpleMeterRegistry()));

    RouteOptimizationService service;

    Location origin = new Location("서울역", 37.5547, 126.9706);
    Location dest   = new Location("강남역", 37.4979, 127.0276);

    @BeforeEach
    void setUp() {
        RouteSearchStrategyRegistry strategyRegistry = new RouteSearchStrategyRegistry(
                transitRoutePort, mobilityTimePort, mobilityAvailabilityPort, hubSelector, routeEvaluator, TransitBudgetPort.UNLIMITED);
        service = new RouteOptimizationService(strategyRegistry, enrichmentPipeline, routeHistoryRecorder);
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of());
        when(hubSelector.selectFirstMileHubs(any(), any(), any())).thenReturn(List.of());
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        when(mobilityAvailabilityPort.findSegmentMobility(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.justOrEmpty(Optional.empty()));
        when(mobilityAvailabilityPort.findNearestMobilityHint(anyDouble(), anyDouble(), any(), anyBoolean()))
                .thenReturn(Mono.just(Optional.empty()));
    }

    @Test
    void SPECIFIC_모드_이동수단_없으면_대중교통만_반환한다() {
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 45, 10000, origin, dest, null, null, null);
        when(transitRoutePort.getTransitRoute(any(), any())).thenReturn(Mono.just(List.of(leg)));
        when(routeEvaluator.evaluate(any(Route.class), eq(true), eq(RecommendationPreference.RELIABILITY))).thenAnswer(invocation -> {
            Route route = invocation.getArgument(0);
            return route.withScore(0.5, true);
        });

        List<Route> routes = service.findRoutes(RouteSearchQuery.of(origin, dest, List.of(), SearchMode.SPECIFIC, RecommendationPreference.RELIABILITY)).block();

        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).type()).isEqualTo(RouteType.TRANSIT_ONLY);
    }

    @Test
    void OPTIMAL_모드는_대중교통_경로를_항상_포함한다() {
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 40, 10000, origin, dest, null, null, null);
        when(transitRoutePort.getTransitRoute(any(), any())).thenReturn(Mono.just(List.of(leg)));
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        when(routeEvaluator.evaluate(any(Route.class), any(Route.class), anyInt(), anyBoolean(), eq(RecommendationPreference.RELIABILITY)))
                .thenAnswer(invocation -> {
                    Route route = invocation.getArgument(0);
                    boolean recommended = invocation.getArgument(3);
                    return route.withScore(0.5, recommended);
                });
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of());
        when(hubSelector.selectFirstMileHubs(any(), any(), any())).thenReturn(List.of());
        List<Route> routes = service.findRoutes(RouteSearchQuery.of(origin, dest, List.of(), SearchMode.OPTIMAL, RecommendationPreference.RELIABILITY)).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.stream().anyMatch(r -> r.type() == RouteType.TRANSIT_ONLY)).isTrue();
    }

    @Test
    void recommendationPreference가_TIME_PRIORITY여도_경로를_반환한다() {
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 40, 10000, origin, dest, null, null, null);
        when(transitRoutePort.getTransitRoute(any(), any())).thenReturn(Mono.just(List.of(leg)));
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        when(routeEvaluator.evaluate(any(Route.class), any(Route.class), anyInt(), anyBoolean(), eq(RecommendationPreference.TIME_PRIORITY)))
                .thenAnswer(invocation -> {
                    Route route = invocation.getArgument(0);
                    boolean recommended = invocation.getArgument(3);
                    return route.withScore(0.7, recommended);
                });
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of());
        when(hubSelector.selectFirstMileHubs(any(), any(), any())).thenReturn(List.of());

        List<Route> routes = service.findRoutes(RouteSearchQuery.of(origin, dest, List.of(), SearchMode.OPTIMAL, RecommendationPreference.TIME_PRIORITY)).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.getFirst().recommended()).isTrue();
    }
}
