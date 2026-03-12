package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteScoreCalculatorTest {

    private final RouteScoreCalculator calculator = new RouteScoreCalculator();

    @Test
    void 시간이_짧을수록_점수가_높다() {
        Route faster = routeWithMinutes(20, 0, 1250);
        Route slower = routeWithMinutes(45, 0, 1250);

        double fastScore = calculator.calculate(faster);
        double slowScore = calculator.calculate(slower);

        assertThat(fastScore).isGreaterThan(slowScore);
    }

    @Test
    void 환승이_적을수록_점수가_높다() {
        Route lessTransfer = routeWithMinutes(30, 1, 1250);
        Route moreTransfer = routeWithMinutes(30, 3, 1250);

        double lessScore = calculator.calculate(lessTransfer);
        double moreScore = calculator.calculate(moreTransfer);

        assertThat(lessScore).isGreaterThan(moreScore);
    }

    @Test
    void 점수는_0에서_1_사이다() {
        Route route = routeWithMinutes(30, 2, 1250);

        double score = calculator.calculate(route);

        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    void 접근_도보가_길수록_점수가_낮다() {
        Location a = new Location("A", 37.5, 127.0);
        Location hub = new Location("Hub", 37.51, 127.01);
        Location b = new Location("B", 37.4, 127.1);
        MobilityInfo bike = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100, "정류소", 37.51, 127.01, 5, 50)
                .withDropoffStation("D1", "반납", 37.4, 127.1);

        Route shortAccess = Route.of(List.of(
                new Leg(LegType.TRANSIT, "BUS", 15, 1200, a, hub, null, null, null),
                new Leg(LegType.WALK, "WALK", 2, 120, hub, hub, null, null, null),
                new Leg(LegType.BIKE, "DDAREUNGI", 10, 1500, hub, b, null, bike, null)
        ), RouteType.TRANSIT_WITH_BIKE);

        Route longAccess = Route.of(List.of(
                new Leg(LegType.TRANSIT, "BUS", 15, 1200, a, hub, null, null, null),
                new Leg(LegType.WALK, "WALK", 7, 520, hub, hub, null, null, null),
                new Leg(LegType.BIKE, "DDAREUNGI", 10, 1500, hub, b, null, bike, null)
        ), RouteType.TRANSIT_WITH_BIKE);

        assertThat(calculator.calculate(shortAccess)).isGreaterThan(calculator.calculate(longAccess));
    }

    @Test
    void 반납_정류소가_없으면_점수가_낮다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.4, 127.1);
        MobilityInfo stableBike = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100, "정류소", 37.5, 127.0, 5, 50)
                .withDropoffStation("D1", "반납", 37.4, 127.1);
        MobilityInfo unstableBike = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100, "정류소", 37.5, 127.0, 1, 50);

        Route withDropoff = Route.of(List.of(
                new Leg(LegType.BIKE, "DDAREUNGI", 18, 2200, a, b, null, stableBike, null)
        ), RouteType.MOBILITY_ONLY);

        Route withoutDropoff = Route.of(List.of(
                new Leg(LegType.BIKE, "DDAREUNGI", 18, 2200, a, b, null, unstableBike, null)
        ), RouteType.MOBILITY_ONLY);

        assertThat(calculator.calculate(withDropoff)).isGreaterThan(calculator.calculate(withoutDropoff));
    }

    @Test
    void 평가_데이터에_허브와_세부점수가_포함된다() {
        Location subway = new Location("서울역", 37.55, 126.97);
        Location bikeStation = new Location("171. 임광빌딩 앞", 37.56, 126.98);
        Location dest = new Location("목적지", 37.57, 126.99);
        MobilityInfo bike = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100,
                "171. 임광빌딩 앞", 37.56, 126.98, 4, 50)
                .withDropoffStation("D1", "반납", 37.57, 126.99);

        Route route = Route.of(List.of(
                new Leg(LegType.TRANSIT, "SUBWAY", 12, 3200, subway, subway,
                        new TransitInfo("5호선", "#996CAC", 4, 1400, List.of(subway)), null, null),
                new Leg(LegType.WALK, "WALK", 2, 120, subway, bikeStation, null, null, null),
                new Leg(LegType.BIKE, "DDAREUNGI", 9, 1800, bikeStation, dest, null, bike, null)
        ), RouteType.TRANSIT_WITH_BIKE);

        RouteEvaluation evaluation = calculator.evaluate(route);

        assertThat(evaluation.totalScore()).isBetween(0.0, 1.0);
        assertThat(evaluation.hubs())
                .extracting(RouteHub::type)
                .contains(com.blackmamba.navigation.domain.hub.HubType.SUBWAY_STATION,
                        com.blackmamba.navigation.domain.hub.HubType.BIKE_STATION);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private Route routeWithMinutes(int minutes, int transferCount, int cost) {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.4, 127.1);
        List<Leg> legs = new ArrayList<>();
        for (int i = 0; i <= transferCount; i++) {
            legs.add(new Leg(LegType.TRANSIT, "BUS", minutes / (transferCount + 1),
                    1000, a, b, null, null, null));
        }
        return new Route("id", RouteType.TRANSIT_ONLY, minutes, cost,
                new RouteCostBreakdown(List.of(new CostComponent("대중교통", cost)), cost),
                List.of(), null, 0, false, legs, null, null);
    }
}
