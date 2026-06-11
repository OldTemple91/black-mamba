package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
    // C-2: CarbonFootprintCalculator는 순수 계산 컴포넌트 → 실제 인스턴스 주입
    @org.mockito.Spy CarbonFootprintCalculator carbonFootprintCalculator = new CarbonFootprintCalculator();
    // F-1: CarReferenceCalculator는 순수 계산 컴포넌트 → 실제 인스턴스 주입
    @org.mockito.Spy CarReferenceCalculator carReferenceCalculator = new CarReferenceCalculator(carbonFootprintCalculator);
    // C-3: AccessibilityPostProcessor도 순수 후처리 → 실제 인스턴스
    @org.mockito.Spy AccessibilityPostProcessor accessibilityPostProcessor =
            new AccessibilityPostProcessor(new AccessibilityStationRegistry());
    // A-4: WeatherAwareRouteAdjuster 도 순수 후처리 → 실제 인스턴스
    @org.mockito.Spy WeatherAwareRouteAdjuster weatherAwareRouteAdjuster = new WeatherAwareRouteAdjuster();
    // RAG Phase 2: Recorder 는 비동기 fire-and-forget이라 mock 으로 주입 (호출 검증 불필요)
    @Mock RouteHistoryRecorder routeHistoryRecorder;

    @InjectMocks RouteOptimizationService service;

    Location origin = new Location("서울역", 37.5547, 126.9706);
    Location dest   = new Location("강남역", 37.4979, 127.0276);

    @BeforeEach
    void setUp() {
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

        List<Route> routes = service.findRoutes(origin, dest, List.of(), SearchMode.SPECIFIC).block();

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
        List<Route> routes = service.findRoutes(origin, dest, List.of(), SearchMode.OPTIMAL).block();

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

        List<Route> routes = service.findRoutes(origin, dest, List.of(), SearchMode.OPTIMAL, RecommendationPreference.TIME_PRIORITY).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.getFirst().recommended()).isTrue();
    }
}
