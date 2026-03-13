package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.*;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.hub.HubType;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OptimalSearchStrategyTest {

    @Mock TransitRoutePort transitRoutePort;
    @Mock MobilityTimePort mobilityTimePort;
    @Mock MobilityAvailabilityPort mobilityAvailabilityPort;
    @Mock HubSelector hubSelector;
    @Mock RouteEvaluator routeEvaluator;

    OptimalSearchStrategy strategy;
    Location origin      = new Location("서울역", 37.5547, 126.9706);
    Location destination = new Location("강남역", 37.4979, 127.0276);
    Location candidate   = new Location("중간역", 37.52, 127.0);
    Leg baseLeg;

    @BeforeEach
    void setUp() {
        strategy = new OptimalSearchStrategy(
                transitRoutePort, mobilityTimePort,
                mobilityAvailabilityPort, hubSelector, routeEvaluator, RecommendationPreference.RELIABILITY);
        baseLeg = new Leg(LegType.TRANSIT, "BUS", 40, 10000, origin, destination, null, null, null);
        when(transitRoutePort.getTransitRoute(any(), any()))
                .thenReturn(Mono.just(List.of(baseLeg)));
        lenient().when(transitRoutePort.getTransitTimeMinutes(any(), any()))
                .thenReturn(Mono.just(20));
        lenient().when(mobilityTimePort.getWalkingRoute(any(), any()))
                .thenReturn(Mono.just(MobilityRouteResult.timeOnly(3)));
        lenient().when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of());
        lenient().when(hubSelector.selectFirstMileHubs(any(), any(), any())).thenReturn(List.of());
        lenient().when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        lenient().when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        lenient().when(routeEvaluator.evaluate(any(Route.class), any(Route.class), anyInt(), anyBoolean(), eq(RecommendationPreference.RELIABILITY)))
                .thenAnswer(invocation -> {
                    Route route = invocation.getArgument(0);
                    boolean recommended = invocation.getArgument(3);
                    double score = route.type() == RouteType.TRANSIT_ONLY ? 0.8 : 0.5;
                    return route.withScore(score, recommended);
                });
    }

    @Test
    void 대중교통_기본_경로는_항상_포함된다() {
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of());
        when(hubSelector.selectFirstMileHubs(any(), any(), any())).thenReturn(List.of());
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.stream().anyMatch(r -> r.type() == RouteType.TRANSIT_ONLY)).isTrue();
    }

    @Test
    void 이동수단만_경로가_거리_범위_내이면_패턴E_포함된다() {
        // 강남→서울역 직선 약 7km → 킥보드(5km) 범위 초과, 따릉이(10km) 범위 이내
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of());
        when(hubSelector.selectFirstMileHubs(any(), any(), any())).thenReturn(List.of());
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.DDAREUNGI, "따릉이",
                                null, 100, "서울역", 37.5547, 126.9706, 5, 0))));
        when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.DDAREUNGI, "따릉이",
                                null, 100, "강남역", 37.4979, 127.0276, 3, 0))));
        when(mobilityTimePort.getMobilityRoute(any(), any(), any()))
                .thenReturn(Mono.just(MobilityRouteResult.timeOnly(30)));

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes.stream().anyMatch(r -> r.type() == RouteType.MOBILITY_ONLY)).isTrue();
    }

    @Test
    void 최적_경로_1위에는_추천_표시가_붙는다() {
        // Pattern C (last-mile): select 가 candidate 반환, selectFirstMile 은 빈 리스트 → Pattern D 미실행
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of(hub(candidate)));
        when(hubSelector.selectFirstMileHubs(any(), any(), any())).thenReturn(List.of());
        when(mobilityTimePort.getMobilityRoute(any(), any(), any()))
                .thenReturn(Mono.just(MobilityRouteResult.timeOnly(8)));
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.KICKBOARD_SHARED, "씽씽",
                                "K001", 80, null, 37.52, 127.0, 1, 100))));
        lenient().when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes.stream().anyMatch(Route::recommended)).isTrue();
        assertThat(routes.stream().filter(Route::recommended)).hasSize(1);
    }

    @Test
    void 대중교통_점수가_더_높으면_대중교통이_추천된다() {
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of(hub(candidate)));
        when(hubSelector.selectFirstMileHubs(any(), any(), any())).thenReturn(List.of());
        when(mobilityTimePort.getMobilityRoute(any(), any(), any()))
                .thenReturn(Mono.just(MobilityRouteResult.timeOnly(8)));
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.DDAREUNGI, "따릉이",
                                null, 100, "정류소", 37.52, 127.0, 5, 100))));
        when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.DDAREUNGI, "따릉이",
                                null, 100, "반납", 37.50, 127.02, 5, 100))));

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.getFirst().type()).isEqualTo(RouteType.TRANSIT_ONLY);
        assertThat(routes.getFirst().recommended()).isTrue();
    }

    @Test
    void 같은_따릉이_정류소_대여반납_조합은_혼합경로에서_제외된다() {
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of(hub(candidate)));
        when(hubSelector.selectFirstMileHubs(any(), any(), any())).thenReturn(List.of(hub(candidate)));
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), eq(MobilityType.DDAREUNGI)))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.DDAREUNGI, "따릉이",
                                null, 100, "142. 아현역 4번출구 앞", 37.52, 127.0, 5, 20)
                                .withDropoffStation("S-142", "142. 아현역 4번출구 앞", 37.52, 127.0))));
        when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), eq(MobilityType.DDAREUNGI)))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.DDAREUNGI, "따릉이",
                                null, 100, "142. 아현역 4번출구 앞", 37.52, 127.0, 5, 20))));
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), eq(MobilityType.PERSONAL)))
                .thenReturn(Mono.just(Optional.empty()));
        when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), eq(MobilityType.PERSONAL)))
                .thenReturn(Mono.just(Optional.empty()));

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes).hasSize(1);
        assertThat(routes.getFirst().type()).isEqualTo(RouteType.TRANSIT_ONLY);
    }

    @Test
    void ODsay_기본경로가_비어도_추천경로가_0분이_아니다() {
        when(transitRoutePort.getTransitRoute(any(), any())).thenReturn(Mono.just(List.of()));
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of());
        when(hubSelector.selectFirstMileHubs(any(), any(), any())).thenReturn(List.of());
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.get(0).totalMinutes()).isGreaterThan(0);
    }

    @Test
    void 혼합경로를_만들지_못하면_미생성_사유를_남긴다() {
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of(hub(candidate)));
        when(hubSelector.selectFirstMileHubs(any(), any(), any())).thenReturn(List.of());
        when(mobilityAvailabilityPort.findNearbyMobility(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));
        when(mobilityAvailabilityPort.findNearbyDropoff(anyDouble(), anyDouble(), any()))
                .thenReturn(Mono.just(Optional.empty()));

        List<Route> routes = strategy.search(origin, destination).block();

        assertThat(routes).hasSize(1);
        assertThat(routes.getFirst().type()).isEqualTo(RouteType.TRANSIT_ONLY);
        assertThat(routes.getFirst().insights()).isNotNull();
        assertThat(routes.getFirst().insights().generationDiagnostics()).isNotEmpty();
    }

    private Hub hub(Location location) {
        return new Hub("hub-1", location.name(), HubType.MOBILITY_TRANSFER_POINT, location, 150, Map.of());
    }
}
