package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.*;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.hub.HubType;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecificMobilityStrategyTest {

    @Mock TransitRoutePort transitRoutePort;
    @Mock MobilityTimePort mobilityTimePort;
    @Mock MobilityAvailabilityPort mobilityAvailabilityPort;
    @Mock HubSelector hubSelector;
    @Mock RouteScoreCalculator scoreCalculator;
    @Mock RouteInsightFactory routeInsightFactory;

    @Test
    void 이동수단_없으면_대중교통만_반환한다() {
        Location origin = new Location("서울역", 37.5547, 126.9706);
        Location dest   = new Location("강남역", 37.4979, 127.0276);
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 45, 10000, origin, dest, null, null, null);

        when(transitRoutePort.getTransitRoute(any(), any()))
                .thenReturn(Mono.just(List.of(leg)));
        when(scoreCalculator.calculate(any())).thenReturn(0.5);
        when(routeInsightFactory.enrich(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        SpecificMobilityStrategy strategy = new SpecificMobilityStrategy(
                List.of(), transitRoutePort, mobilityTimePort,
                mobilityAvailabilityPort, hubSelector, scoreCalculator, routeInsightFactory);

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
        Leg baseLeg = new Leg(LegType.TRANSIT, "BUS", 45, 10000, origin, dest, null, null, null);
        Leg partialLeg = new Leg(LegType.TRANSIT, "BUS", 18, 4000, origin, candidate, null, null, null);

        when(transitRoutePort.getTransitRoute(eq(origin), eq(dest)))
                .thenReturn(Mono.just(List.of(baseLeg)));
        when(transitRoutePort.getTransitRoute(eq(origin), eq(candidate)))
                .thenReturn(Mono.just(List.of(partialLeg)));
        when(transitRoutePort.getTransitTimeMinutes(any(), any())).thenReturn(Mono.just(18));
        when(mobilityTimePort.getMobilityRoute(any(), any(), any()))
                .thenReturn(Mono.just(MobilityRouteResult.timeOnly(9)));
        when(mobilityAvailabilityPort.findNearbyMobility(any(Double.class), any(Double.class), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.KICKBOARD_SHARED, "씽씽",
                                "DEV_001", 85, null, 37.52, 127.0, 0, 120))));
        when(hubSelector.selectLastMileHubs(any(), any())).thenReturn(List.of(hub(candidate)));
        when(scoreCalculator.calculate(any())).thenReturn(0.8, 0.5);
        when(routeInsightFactory.enrich(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        SpecificMobilityStrategy strategy = new SpecificMobilityStrategy(
                List.of(MobilityType.KICKBOARD_SHARED), transitRoutePort, mobilityTimePort,
                mobilityAvailabilityPort, hubSelector, scoreCalculator, routeInsightFactory);

        List<Route> routes = strategy.search(origin, dest).block();

        assertThat(routes.get(0).recommended()).isTrue();
        assertThat(routes.get(0).totalMinutes()).isEqualTo(27);
    }

    private Hub hub(Location location) {
        return new Hub("hub-1", location.name(), HubType.MOBILITY_TRANSFER_POINT, location, 150, Map.of());
    }
}
