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
        baseLeg = new Leg(LegType.TRANSIT, "BUS", 40, 10000, origin, destination, null, null, null);
        when(transitRoutePort.getTransitRoute(any(), any()))
                .thenReturn(Mono.just(List.of(baseLeg)));
    }

    @Test
    void 대중교통_기본_경로는_항상_포함된다() {
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
        when(mobilityTimePort.getMobilityRoute(any(), any(), any()))
                .thenReturn(Mono.just(MobilityRouteResult.timeOnly(30)));
        when(scoreCalculator.calculate(any())).thenReturn(0.6, 0.5);

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes.stream().anyMatch(r -> r.type() == RouteType.MOBILITY_ONLY)).isTrue();
    }

    @Test
    void 최적_경로_1위에는_추천_표시가_붙는다() {
        // Pattern C (last-mile): select 가 candidate 반환, selectFirstMile 은 빈 리스트 → Pattern D 미실행
        when(candidatePointSelector.select(any(), any())).thenReturn(List.of(candidate));
        when(candidatePointSelector.selectFirstMile(any(), any(), any())).thenReturn(List.of());
        when(mobilityTimePort.getMobilityRoute(any(), any(), any()))
                .thenReturn(Mono.just(MobilityRouteResult.timeOnly(8)));
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.KICKBOARD_SHARED, "씽씽",
                                "K001", 80, null, 37.52, 127.0, 1, 100))));
        when(scoreCalculator.calculate(any())).thenReturn(0.9, 0.8, 0.7, 0.5);

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes.get(0).recommended()).isTrue();
        assertThat(routes.stream().filter(Route::recommended)).hasSize(1);
    }

    @Test
    void ODsay_기본경로가_비어도_추천경로가_0분이_아니다() {
        when(transitRoutePort.getTransitRoute(any(), any())).thenReturn(Mono.just(List.of()));
        when(candidatePointSelector.select(any(), any())).thenReturn(List.of());
        when(candidatePointSelector.selectFirstMile(any(), any(), any())).thenReturn(List.of());
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.get(0).totalMinutes()).isGreaterThan(0);
    }
}
