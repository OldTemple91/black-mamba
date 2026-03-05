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

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private Route routeWithMinutes(int minutes, int transferCount, int cost) {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.4, 127.1);
        List<Leg> legs = new ArrayList<>();
        for (int i = 0; i <= transferCount; i++) {
            legs.add(new Leg(LegType.TRANSIT, "BUS", minutes / (transferCount + 1),
                    1000, a, b, null, null));
        }
        return new Route("id", RouteType.TRANSIT_ONLY, minutes, cost, 0, false, legs, null);
    }
}
