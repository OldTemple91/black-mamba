package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @Test
    void 시간_우선_모드는_빠른_혼합경로에_더_유리한_점수를_준다() {
        Location a = new Location("A", 37.5, 127.0);
        Location hub = new Location("Hub", 37.51, 127.01);
        Location b = new Location("B", 37.4, 127.1);
        MobilityInfo stableBike = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100,
                "정류소", 37.51, 127.01, 6, 50)
                .withDropoffStation("D1", "반납", 37.4, 127.1);

        Route reliableTransit = Route.of(List.of(
                new Leg(LegType.TRANSIT, "BUS", 36, 1800, a, b, null, null, null),
                new Leg(LegType.WALK, "WALK", 4, 260, b, b, null, null, null)
        ), RouteType.TRANSIT_ONLY);

        Route fasterButRiskyMixed = Route.of(List.of(
                new Leg(LegType.TRANSIT, "BUS", 10, 1200, a, hub, null, null, null),
                new Leg(LegType.WALK, "WALK", 4, 320, hub, hub, null, null, null),
                new Leg(LegType.BIKE, "DDAREUNGI", 13, 1800, hub, b, null, stableBike, null)
        ), RouteType.TRANSIT_WITH_BIKE);

        double reliabilityScoreTransit = calculator.calculate(reliableTransit, RecommendationPreference.RELIABILITY);
        double reliabilityScoreMixed = calculator.calculate(fasterButRiskyMixed, RecommendationPreference.RELIABILITY);
        double timePriorityScoreTransit = calculator.calculate(reliableTransit, RecommendationPreference.TIME_PRIORITY);
        double timePriorityScoreMixed = calculator.calculate(fasterButRiskyMixed, RecommendationPreference.TIME_PRIORITY);

        assertThat(reliabilityScoreTransit).isGreaterThan(reliabilityScoreMixed);
        assertThat(timePriorityScoreTransit).isLessThan(reliabilityScoreTransit);
        assertThat(timePriorityScoreMixed).isGreaterThan(timePriorityScoreTransit);
        assertThat(timePriorityScoreMixed).isGreaterThan(reliabilityScoreMixed);
        assertThat(timePriorityScoreMixed).isGreaterThan(timePriorityScoreTransit);
    }

    @Test
    void 대여소_힌트가_먼_허브는_점수가_낮다() {
        Location a = new Location("A", 37.5, 127.0);
        Location hub = new Location("Hub", 37.51, 127.01);
        Location b = new Location("B", 37.4, 127.1);
        MobilityInfo stableBike = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100,
                "정류소", 37.51, 127.01, 6, 50)
                .withDropoffStation("D1", "반납", 37.4, 127.1);

        Route betterPickupHint = Route.of(List.of(
                new Leg(LegType.TRANSIT, "BUS", 10, 1200, a, hub, null, null, null),
                new Leg(LegType.BIKE, "DDAREUNGI", 13, 1800, hub, b, null, stableBike, null)
        ), RouteType.TRANSIT_WITH_BIKE).withSelectedHubs(List.of(
                new RouteHub("Hub", com.blackmamba.navigation.domain.hub.HubType.MOBILITY_TRANSFER_POINT,
                        "LAST_MILE_CANDIDATE", "selected-candidate",
                        Map.of("pickupHintDistanceMeters", "420"))
        ));

        Route worsePickupHint = Route.of(List.of(
                new Leg(LegType.TRANSIT, "BUS", 10, 1200, a, hub, null, null, null),
                new Leg(LegType.BIKE, "DDAREUNGI", 13, 1800, hub, b, null, stableBike, null)
        ), RouteType.TRANSIT_WITH_BIKE).withSelectedHubs(List.of(
                new RouteHub("Hub", com.blackmamba.navigation.domain.hub.HubType.MOBILITY_TRANSFER_POINT,
                        "LAST_MILE_CANDIDATE", "selected-candidate",
                        Map.of("pickupHintDistanceMeters", "1021"))
        ));

        assertThat(calculator.calculate(betterPickupHint)).isGreaterThan(calculator.calculate(worsePickupHint));
    }

    @Test
    void 앵커에서_너무_먼_허브는_점수가_낮다() {
        Location a = new Location("A", 37.5, 127.0);
        Location hub = new Location("Hub", 37.51, 127.01);
        Location b = new Location("B", 37.4, 127.1);
        MobilityInfo stableBike = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100,
                "정류소", 37.51, 127.01, 6, 50)
                .withDropoffStation("D1", "반납", 37.4, 127.1);

        Route betterAnchorFit = Route.of(List.of(
                new Leg(LegType.TRANSIT, "BUS", 10, 1200, a, hub, null, null, null),
                new Leg(LegType.BIKE, "DDAREUNGI", 13, 1800, hub, b, null, stableBike, null)
        ), RouteType.TRANSIT_WITH_BIKE).withSelectedHubs(List.of(
                new RouteHub("Hub", com.blackmamba.navigation.domain.hub.HubType.MOBILITY_TRANSFER_POINT,
                        "LAST_MILE_CANDIDATE", "selected-candidate",
                        Map.of("distanceToAnchorMeters", "850", "pickupHintDistanceMeters", "420"))
        ));

        Route worseAnchorFit = Route.of(List.of(
                new Leg(LegType.TRANSIT, "BUS", 10, 1200, a, hub, null, null, null),
                new Leg(LegType.BIKE, "DDAREUNGI", 13, 1800, hub, b, null, stableBike, null)
        ), RouteType.TRANSIT_WITH_BIKE).withSelectedHubs(List.of(
                new RouteHub("Hub", com.blackmamba.navigation.domain.hub.HubType.MOBILITY_TRANSFER_POINT,
                        "LAST_MILE_CANDIDATE", "selected-candidate",
                        Map.of("distanceToAnchorMeters", "2802", "pickupHintDistanceMeters", "420"))
        ));

        assertThat(calculator.calculate(betterAnchorFit)).isGreaterThan(calculator.calculate(worseAnchorFit));
    }

    @Test
    void 최대값_초과시_정규화는_1로_클램핑된다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.4, 127.1);

        List<Leg> legs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            legs.add(new Leg(LegType.TRANSIT, "BUS", 15, 1000, a, b, null, null, null));
        }
        Route route = new Route("id", RouteType.TRANSIT_ONLY, 120, 8000,
                new RouteCostBreakdown(List.of(new CostComponent("대중교통", 8000)), 8000),
                List.of(), null, 0, false, legs, null, null);

        RouteEvaluation evaluation = calculator.evaluate(route);

        assertThat(evaluation.timeScore()).isEqualTo(0.0);
        assertThat(evaluation.costScore()).isEqualTo(0.0);
        assertThat(evaluation.transferScore()).isEqualTo(0.0);
        assertThat(evaluation.totalScore()).isBetween(0.0, 1.0);
    }

    @Test
    void 다중_신뢰도_페널티_누적시_점수가_대폭_하락한다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.4, 127.1);
        MobilityInfo unstableBike = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 15,
                "정류소", 37.5, 127.0, 1, 50);

        Route penaltyRoute = Route.of(List.of(
                new Leg(LegType.BIKE, "DDAREUNGI", 18, 2200, a, b, null, unstableBike, null)
        ), RouteType.MOBILITY_ONLY);

        MobilityInfo stableBike = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100,
                "정류소", 37.5, 127.0, 10, 50)
                .withDropoffStation("D1", "반납", 37.4, 127.1);

        Route cleanRoute = Route.of(List.of(
                new Leg(LegType.BIKE, "DDAREUNGI", 18, 2200, a, b, null, stableBike, null)
        ), RouteType.MOBILITY_ONLY);

        RouteEvaluation penaltyEval = calculator.evaluate(penaltyRoute);
        RouteEvaluation cleanEval = calculator.evaluate(cleanRoute);

        assertThat(penaltyEval.sharedMobilityDependent()).isTrue();
        assertThat(penaltyEval.weakDropoff()).isTrue();
        assertThat(penaltyEval.lowAvailability()).isTrue();
        assertThat(penaltyEval.reliabilityScore()).isLessThan(cleanEval.reliabilityScore());
        assertThat(penaltyEval.totalScore()).isLessThan(cleanEval.totalScore());
    }

    @Test
    void 대중교통만_경로는_신뢰도_페널티가_없다() {
        Route transitOnly = routeWithMinutes(30, 1, 1250);

        RouteEvaluation evaluation = calculator.evaluate(transitOnly);

        assertThat(evaluation.sharedMobilityDependent()).isFalse();
        assertThat(evaluation.weakDropoff()).isFalse();
        assertThat(evaluation.lowAvailability()).isFalse();
        assertThat(evaluation.lowBattery()).isFalse();
        assertThat(evaluation.reliabilityScore()).isEqualTo(1.0);
    }

    @Test
    void 영값_경로는_만점에_가깝다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.4, 127.1);

        Route zeroRoute = new Route("id", RouteType.TRANSIT_ONLY, 0, 0,
                new RouteCostBreakdown(List.of(new CostComponent("대중교통", 0)), 0),
                List.of(), null, 0, false,
                List.of(new Leg(LegType.TRANSIT, "BUS", 0, 0, a, b, null, null, null)),
                null, null);

        double score = calculator.calculate(zeroRoute);

        assertThat(score).isGreaterThanOrEqualTo(0.95);
    }

    @Test
    void 가용성_부족시_점수가_하락한다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.4, 127.1);

        MobilityInfo lowAvailBike = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100,
                "정류소", 37.5, 127.0, 1, 50)
                .withDropoffStation("D1", "반납", 37.4, 127.1);

        MobilityInfo highAvailBike = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100,
                "정류소", 37.5, 127.0, 10, 50)
                .withDropoffStation("D1", "반납", 37.4, 127.1);

        Route lowAvailRoute = Route.of(List.of(
                new Leg(LegType.BIKE, "DDAREUNGI", 18, 2200, a, b, null, lowAvailBike, null)
        ), RouteType.MOBILITY_ONLY);

        Route highAvailRoute = Route.of(List.of(
                new Leg(LegType.BIKE, "DDAREUNGI", 18, 2200, a, b, null, highAvailBike, null)
        ), RouteType.MOBILITY_ONLY);

        RouteEvaluation lowEval = calculator.evaluate(lowAvailRoute);
        RouteEvaluation highEval = calculator.evaluate(highAvailRoute);

        assertThat(lowEval.lowAvailability()).isTrue();
        assertThat(highEval.lowAvailability()).isFalse();
        assertThat(lowEval.totalScore()).isLessThan(highEval.totalScore());
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
