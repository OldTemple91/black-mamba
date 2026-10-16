package com.blackmamba.navigation.domain.route.evaluation;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.domain.route.TransitInfo;
import com.blackmamba.navigation.domain.route.mobility.AvailabilitySource;
import com.blackmamba.navigation.domain.route.mobility.MobilityInfo;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteReliabilityMetricsTest {

    private static final Location A = new Location("A", 37.5, 127.0);
    private static final Location B = new Location("B", 37.51, 127.01);
    private static final Location C = new Location("C", 37.52, 127.02);
    private static final Location D = new Location("D", 37.53, 127.03);
    private static final Location E = new Location("E", 37.54, 127.04);

    @Test
    void 도보구간_거리합산() {
        Leg walk1 = walkLeg(300);
        Leg transit = transitLeg();
        Leg walk2 = walkLeg(500);

        Route route = Route.of(List.of(walk1, transit, walk2), RouteType.TRANSIT_ONLY);

        assertThat(RouteReliabilityMetrics.walkingDistance(route)).isEqualTo(800);
    }

    @Test
    void 환승횟수_계산() {
        Leg walk1 = walkLeg(100);
        Leg transit1 = transitLeg();
        Leg walk2 = walkLeg(50);
        Leg transit2 = transitLeg();
        Leg walk3 = walkLeg(50);
        Leg transit3 = transitLeg();

        Route route = Route.of(
                List.of(walk1, transit1, walk2, transit2, walk3, transit3),
                RouteType.TRANSIT_ONLY
        );

        assertThat(RouteReliabilityMetrics.transferCount(route)).isEqualTo(2);
    }

    @Test
    void TRANSIT_하나일때_환승_0() {
        Leg walk1 = walkLeg(200);
        Leg transit = transitLeg();
        Leg walk2 = walkLeg(300);

        Route route = Route.of(List.of(walk1, transit, walk2), RouteType.TRANSIT_ONLY);

        assertThat(RouteReliabilityMetrics.transferCount(route)).isZero();
    }

    @Test
    void 공유이동수단_감지() {
        MobilityInfo sharedInfo = new MobilityInfo(
                MobilityType.KICKBOARD_SHARED, "씽씽", "device-1", 80,
                "스테이션A", 37.5, 127.0, 5, 100
        );
        Leg kickboard = new Leg(LegType.KICKBOARD, "KICKBOARD_SHARED", 10, 2000, A, B, null, sharedInfo, null);

        Route route = Route.of(List.of(kickboard), RouteType.MOBILITY_ONLY);

        assertThat(RouteReliabilityMetrics.hasSharedMobility(route)).isTrue();
    }

    @Test
    void 개인이동수단은_운영사명과_무관하게_공유가_아님() {
        // given — 어댑터가 실제로 넣는 운영사명 ("개인 킥보드")
        MobilityInfo personalInfo = new MobilityInfo(
                MobilityType.PERSONAL_KICKBOARD, "개인 킥보드", null, 100,
                null, 37.5, 127.0, 1, 0
        );
        Leg kickboard = new Leg(LegType.KICKBOARD, "PERSONAL_KICKBOARD", 15, 3000, A, B, null, personalInfo, null);
        Route route = Route.of(List.of(kickboard), RouteType.MOBILITY_ONLY);

        // when & then
        assertThat(RouteReliabilityMetrics.hasSharedMobility(route)).isFalse();
        assertThat(RouteReliabilityMetrics.hasHealthyKickboard(route)).isFalse();   // 읽은 적 없는 배터리로 근거를 만들지 않는다
        assertThat(RouteReliabilityMetrics.hasLowBattery(route)).isFalse();
    }

    @Test
    void 추정_가용성_킥보드는_대여여유_부족으로_본다() {
        // given — TAGO 미제공 시 어댑터가 만드는 synthetic 킥보드 (ESTIMATED)
        MobilityInfo estimated = new MobilityInfo(
                MobilityType.KICKBOARD_SHARED, "공유 킥보드(추정)", null, 50,
                null, null, 0, 37.5, 127.0, 1, 0, null, null, 0.0, 0.0, AvailabilitySource.ESTIMATED
        );
        Leg kickboard = new Leg(LegType.KICKBOARD, "KICKBOARD_SHARED", 10, 2000, A, B, null, estimated, null);
        Route route = Route.of(List.of(kickboard), RouteType.MOBILITY_ONLY);

        // when & then
        assertThat(RouteReliabilityMetrics.hasLowAvailability(route)).isTrue();
        assertThat(RouteReliabilityMetrics.hasSharedMobility(route)).isTrue();
    }

    @Test
    void 반납정류소_미확인_감지() {
        MobilityInfo noDropoff = new MobilityInfo(
                MobilityType.DDAREUNGI, "따릉이", null, 100,
                "정류소A", 37.5, 127.0, 5, 50
        );
        Leg bike = new Leg(LegType.BIKE, "DDAREUNGI", 20, 3000, A, B, null, noDropoff, null);

        Route route = Route.of(List.of(bike), RouteType.MOBILITY_ONLY);

        assertThat(RouteReliabilityMetrics.hasWeakDropoff(route)).isTrue();
    }

    @Test
    void 가용성_부족_감지() {
        MobilityInfo lowAvail = new MobilityInfo(
                MobilityType.DDAREUNGI, "따릉이", null, 100,
                "정류소A", 37.5, 127.0, 2, 50
        ).withDropoffStation("D1", "반납소", 37.51, 127.01);
        Leg bike = new Leg(LegType.BIKE, "DDAREUNGI", 20, 3000, A, B, null, lowAvail, null);

        Route route = Route.of(List.of(bike), RouteType.MOBILITY_ONLY);

        assertThat(RouteReliabilityMetrics.hasLowAvailability(route)).isTrue();
    }

    @Test
    void 배터리_부족_감지() {
        MobilityInfo lowBattery = new MobilityInfo(
                MobilityType.KICKBOARD_SHARED, "씽씽", "device-3", 25,
                "스테이션B", 37.5, 127.0, 5, 100
        );
        Leg kickboard = new Leg(LegType.KICKBOARD, "KICKBOARD_SHARED", 10, 2000, A, B, null, lowBattery, null);

        Route route = Route.of(List.of(kickboard), RouteType.MOBILITY_ONLY);

        assertThat(RouteReliabilityMetrics.hasLowBattery(route)).isTrue();
    }

    @Test
    void 대중교통만_경로는_모든_위험지표_false() {
        Leg walk1 = walkLeg(200);
        Leg transit1 = transitLeg();
        Leg walk2 = walkLeg(150);
        Leg transit2 = transitLeg();
        Leg walk3 = walkLeg(100);

        Route route = Route.of(
                List.of(walk1, transit1, walk2, transit2, walk3),
                RouteType.TRANSIT_ONLY
        );

        assertThat(RouteReliabilityMetrics.hasSharedMobility(route)).isFalse();
        assertThat(RouteReliabilityMetrics.hasWeakDropoff(route)).isFalse();
        assertThat(RouteReliabilityMetrics.hasLowAvailability(route)).isFalse();
        assertThat(RouteReliabilityMetrics.hasLowBattery(route)).isFalse();
    }

    @Test
    void 접근도보_최대거리_계산() {
        Leg walk1 = walkLeg(400);
        Leg bike = new Leg(LegType.BIKE, "DDAREUNGI", 15, 2500, B, C, null, null, null);
        Leg walk2 = walkLeg(600);
        Leg transit = transitLeg();
        Leg walk3 = walkLeg(200);

        Route route = Route.of(
                List.of(walk1, bike, walk2, transit, walk3),
                RouteType.TRANSIT_WITH_BIKE
        );

        assertThat(RouteReliabilityMetrics.maxAccessWalkDistance(route)).isEqualTo(600);
    }

    // --- helper methods ---

    private static Leg walkLeg(int distanceMeters) {
        return new Leg(LegType.WALK, "WALK", distanceMeters / 60, distanceMeters, A, B, null, null, null);
    }

    private static Leg transitLeg() {
        TransitInfo info = TransitInfo.of("2호선", "#33A23D", 3);
        return new Leg(LegType.TRANSIT, "SUBWAY", 10, 5000, B, C, info, null, null);
    }
}
