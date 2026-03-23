package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.MobilityTimePort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.MobilityInfo;
import com.blackmamba.navigation.domain.route.MobilityRouteResult;
import com.blackmamba.navigation.domain.route.MobilityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobilitySegmentBuilderTest {

    @Mock MobilityTimePort mobilityTimePort;

    private static final List<Location> DUMMY_COORDS = List.of(
            new Location("p1", 37.55, 126.97),
            new Location("p2", 37.56, 126.98)
    );

    @Test
    void 따릉이_접근도보_이동_하차도보_3구간_생성() {
        // approach(서울역) != pickup(따릉이 정류소) != dropoff(목적지 근처 정류소) != destination(강남역)
        Location approach = new Location("서울역", 37.5547, 126.9706);
        Location destination = new Location("강남역", 37.4979, 127.0276);

        MobilityInfo info = new MobilityInfo(
                MobilityType.DDAREUNGI, null, null, 0,
                "ST-1", "서울역 따릉이", 10,
                37.5560, 126.9720,   // pickup - approach에서 약 170m 떨어짐
                5, 200,
                "ST-2", "강남역 따릉이",
                37.4990, 127.0260,   // dropoff - destination에서 약 170m 떨어짐
                null
        );

        MobilityRouteResult walkRoute = new MobilityRouteResult(3, 170, DUMMY_COORDS);
        MobilityRouteResult bikeRoute = new MobilityRouteResult(20, 8000, DUMMY_COORDS);

        when(mobilityTimePort.getWalkingRoute(any(), any())).thenReturn(Mono.just(walkRoute));
        when(mobilityTimePort.getMobilityRoute(any(), any(), eq(MobilityType.DDAREUNGI)))
                .thenReturn(Mono.just(bikeRoute));

        MobilitySegmentBuilder builder = new MobilitySegmentBuilder(mobilityTimePort);
        List<Leg> legs = builder.build(approach, destination, MobilityType.DDAREUNGI, info).block();

        assertThat(legs).hasSize(3);
        assertThat(legs.get(0).type()).isEqualTo(LegType.WALK);
        assertThat(legs.get(1).type()).isEqualTo(LegType.BIKE);
        assertThat(legs.get(1).mode()).isEqualTo("DDAREUNGI");
        assertThat(legs.get(1).mobilityInfo()).isEqualTo(info);
        assertThat(legs.get(2).type()).isEqualTo(LegType.WALK);
    }

    @Test
    void 개인킥보드는_접근도보_없이_이동구간만_생성() {
        // PERSONAL_KICKBOARD: pickupPoint == approach (fallback) -> 접근 도보 0m
        Location approach = new Location("현위치", 37.5547, 126.9706);
        Location destination = new Location("목적지", 37.4979, 127.0276);

        MobilityInfo info = new MobilityInfo(
                MobilityType.PERSONAL_KICKBOARD, null, "device-1", 80,
                null, 37.5547, 126.9706, 1, 0
        );

        MobilityRouteResult kickRoute = new MobilityRouteResult(15, 6000, DUMMY_COORDS);

        when(mobilityTimePort.getMobilityRoute(any(), any(), eq(MobilityType.PERSONAL_KICKBOARD)))
                .thenReturn(Mono.just(kickRoute));

        MobilitySegmentBuilder builder = new MobilitySegmentBuilder(mobilityTimePort);
        List<Leg> legs = builder.build(approach, destination, MobilityType.PERSONAL_KICKBOARD, info).block();

        // approach == pickup 이므로 접근 도보 없음, dropoff == destination 이므로 하차 도보 없음
        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).type()).isEqualTo(LegType.KICKBOARD);
        assertThat(legs.get(0).mode()).isEqualTo("PERSONAL_KICKBOARD");
    }

    @Test
    void 도보_20m_이하면_도보구간_미생성() {
        // pickup이 approach에서 20m 이내 -> 도보 구간 생략
        Location approach = new Location("현위치", 37.554700, 126.970600);
        Location destination = new Location("목적지", 37.4979, 127.0276);

        // 따릉이 정류소가 approach에서 약 10m 떨어진 위치
        MobilityInfo info = new MobilityInfo(
                MobilityType.DDAREUNGI, null, null, 0,
                "ST-1", "가까운 정류소", 10,
                37.554710, 126.970610,  // approach에서 ~1m
                5, 10,
                null, null,
                0.0, 0.0,
                null
        );

        MobilityRouteResult bikeRoute = new MobilityRouteResult(20, 8000, DUMMY_COORDS);

        when(mobilityTimePort.getMobilityRoute(any(), any(), eq(MobilityType.DDAREUNGI)))
                .thenReturn(Mono.just(bikeRoute));

        MobilitySegmentBuilder builder = new MobilitySegmentBuilder(mobilityTimePort);
        List<Leg> legs = builder.build(approach, destination, MobilityType.DDAREUNGI, info).block();

        // 접근 도보 생략, 하차 도보 생략 (dropoff == destination)
        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).type()).isEqualTo(LegType.BIKE);
    }

    @Test
    void 따릉이_반납정류소_있으면_반납지점_사용() {
        Location approach = new Location("현위치", 37.5547, 126.9706);
        Location destination = new Location("강남역", 37.4979, 127.0276);

        MobilityInfo info = new MobilityInfo(
                MobilityType.DDAREUNGI, null, null, 0,
                "ST-1", "서울역 따릉이", 10,
                37.5547, 126.9706,   // pickup == approach
                5, 0,
                "ST-2", "강남역 따릉이",
                37.4990, 127.0260,   // dropoff 정류소 위치
                null
        );

        MobilityRouteResult bikeRoute = new MobilityRouteResult(20, 8000, DUMMY_COORDS);
        MobilityRouteResult walkRoute = new MobilityRouteResult(3, 170, DUMMY_COORDS);

        when(mobilityTimePort.getMobilityRoute(any(), any(), eq(MobilityType.DDAREUNGI)))
                .thenReturn(Mono.just(bikeRoute));
        when(mobilityTimePort.getWalkingRoute(any(), any()))
                .thenReturn(Mono.just(walkRoute));

        MobilitySegmentBuilder builder = new MobilitySegmentBuilder(mobilityTimePort);
        List<Leg> legs = builder.build(approach, destination, MobilityType.DDAREUNGI, info).block();

        // pickup == approach 이므로 접근 도보 없음
        // dropoff(반납 정류소) != destination 이므로 하차 도보 있음
        Leg mobilityLeg = legs.stream().filter(l -> l.type() == LegType.BIKE).findFirst().orElseThrow();
        assertThat(mobilityLeg.end().name()).isEqualTo("강남역 따릉이");
        assertThat(mobilityLeg.end().lat()).isEqualTo(37.4990);
        assertThat(mobilityLeg.end().lng()).isEqualTo(127.0260);

        Leg egressWalk = legs.get(legs.size() - 1);
        assertThat(egressWalk.type()).isEqualTo(LegType.WALK);
        assertThat(egressWalk.start().name()).isEqualTo("강남역 따릉이");
        assertThat(egressWalk.end().name()).isEqualTo("강남역");
    }

    @Test
    void 따릉이_반납정류소_없으면_목적지_사용() {
        Location approach = new Location("현위치", 37.5547, 126.9706);
        Location destination = new Location("강남역", 37.4979, 127.0276);

        // dropoff 정류소 정보 없음
        MobilityInfo info = new MobilityInfo(
                MobilityType.DDAREUNGI, null, null, 0,
                "서울역 따릉이", 37.5547, 126.9706, 5, 0
        );

        MobilityRouteResult bikeRoute = new MobilityRouteResult(20, 8000, DUMMY_COORDS);

        when(mobilityTimePort.getMobilityRoute(any(), any(), eq(MobilityType.DDAREUNGI)))
                .thenReturn(Mono.just(bikeRoute));

        MobilitySegmentBuilder builder = new MobilitySegmentBuilder(mobilityTimePort);
        List<Leg> legs = builder.build(approach, destination, MobilityType.DDAREUNGI, info).block();

        // dropoff == destination (반납 정류소 없으므로 fallback)
        Leg mobilityLeg = legs.stream().filter(l -> l.type() == LegType.BIKE).findFirst().orElseThrow();
        assertThat(mobilityLeg.end().name()).isEqualTo("강남역");
        assertThat(mobilityLeg.end().lat()).isEqualTo(37.4979);
        assertThat(mobilityLeg.end().lng()).isEqualTo(127.0276);

        // 하차 도보 없음 (dropoff == destination)
        assertThat(legs).hasSize(1);
    }
}
