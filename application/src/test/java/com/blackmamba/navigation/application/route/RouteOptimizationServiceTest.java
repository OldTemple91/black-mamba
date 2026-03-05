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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteOptimizationServiceTest {

    @Mock TransitRoutePort transitRoutePort;
    @Mock MobilityTimePort mobilityTimePort;
    @Mock MobilityAvailabilityPort mobilityAvailabilityPort;
    @Mock CandidatePointSelector candidatePointSelector;
    @Mock RouteScoreCalculator scoreCalculator;

    @InjectMocks RouteOptimizationService service;

    @Test
    void 기본_대중교통_경로를_항상_포함한다() {
        Location origin = new Location("서울역", 37.5547, 126.9706);
        Location destination = new Location("강남역", 37.4979, 127.0276);
        Leg transitLeg = new Leg(LegType.TRANSIT, "BUS", 45, 10000, origin, destination, null, null);

        when(transitRoutePort.getTransitRoute(any(), any()))
                .thenReturn(Mono.just(List.of(transitLeg)));
        when(scoreCalculator.calculate(any())).thenReturn(0.5);

        List<Route> routes = service.findRoutes(origin, destination, List.of()).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.stream().anyMatch(r -> r.type() == RouteType.TRANSIT_ONLY)).isTrue();
    }

    @Test
    void 이동수단_조합_경로가_더_빠르면_추천으로_표시된다() {
        Location origin = new Location("서울역", 37.5547, 126.9706);
        Location destination = new Location("강남역", 37.4979, 127.0276);
        Location candidate = new Location("중간역", 37.5200, 127.0000);

        Leg transitLeg = new Leg(LegType.TRANSIT, "BUS", 45, 10000, origin, destination, null, null);
        when(transitRoutePort.getTransitRoute(any(), any()))
                .thenReturn(Mono.just(List.of(transitLeg)));
        when(transitRoutePort.getTransitTimeMinutes(any(), any()))
                .thenReturn(Mono.just(18));
        when(mobilityTimePort.getMobilityTimeMinutes(any(), any(), any()))
                .thenReturn(Mono.just(9)); // 18+9=27분 < 45분
        when(mobilityAvailabilityPort.findNearbyMobility(any(Double.class), any(Double.class), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.KICKBOARD_SHARED, "씽씽",
                                "DEV_001", 85, null, 37.52, 127.0, 0, 120))));
        when(candidatePointSelector.select(any(), any())).thenReturn(List.of(candidate));
        // 조합 경로가 먼저 스코어 계산됨(0.8), 기본 경로가 두 번째(0.5)
        when(scoreCalculator.calculate(any())).thenReturn(0.8, 0.5);

        List<Route> routes = service.findRoutes(origin, destination,
                List.of(MobilityType.KICKBOARD_SHARED)).block();

        assertThat(routes.get(0).recommended()).isTrue();
        assertThat(routes.get(0).totalMinutes()).isEqualTo(27);
    }
}
