package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

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
        List<Route> routes = service.findRoutes(origin, dest, List.of(), SearchMode.OPTIMAL).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.stream().anyMatch(r -> r.type() == RouteType.TRANSIT_ONLY)).isTrue();
    }
}
