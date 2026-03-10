package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RouteTest {

    @Test
    void 총_소요시간은_모든_Leg의_합이다() {
        Location seoul = new Location("서울역", 37.5547, 126.9706);
        Location banpo = new Location("반포역", 37.5040, 127.0050);
        Location dest = new Location("목적지", 37.4979, 127.0276);

        Leg transitLeg = new Leg(LegType.TRANSIT, "BUS", 18, 4200, seoul, banpo, null, null, null);
        Leg kickboardLeg = new Leg(LegType.KICKBOARD, "KICKBOARD_SHARED", 9, 1800, banpo, dest, null, null, null);

        Route route = Route.of(List.of(transitLeg, kickboardLeg), RouteType.TRANSIT_WITH_KICKBOARD);

        assertThat(route.totalMinutes()).isEqualTo(27);
    }

    @Test
    void 절약_시간을_계산할_수_있다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.4, 127.1);
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 27, 5000, a, b, null, null, null);

        Route route = Route.of(List.of(leg), RouteType.TRANSIT_ONLY);
        Route routeWithSaving = route.withComparison(new Comparison(45, 18));

        assertThat(routeWithSaving.comparison().savedMinutes()).isEqualTo(18);
    }
}
