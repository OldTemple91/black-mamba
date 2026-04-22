package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.Comparison;
import com.blackmamba.navigation.domain.route.CostComponent;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteCostBreakdown;
import com.blackmamba.navigation.domain.route.RouteType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RouteStreamService#changeReason} 의 변경 감지 규칙을 순수 단위 테스트로 검증.
 */
class RouteStreamServiceTest {

    @Test
    void 이전결과_없으면_초기_결과_도착_으로_감지() {
        List<Route> cur = List.of(recommended("rt_1", RouteType.TRANSIT_ONLY, 30));
        assertThat(RouteStreamService.changeReason(null, cur))
                .isEqualTo("초기 결과 도착");
    }

    @Test
    void 현재_결과_비어있으면_null_반환() {
        assertThat(RouteStreamService.changeReason(List.of(), List.of())).isNull();
    }

    @Test
    void 추천_경로_routeId_변경시_UPDATE_이유_반환() {
        List<Route> prev = List.of(recommended("rt_1", RouteType.TRANSIT_ONLY, 30));
        List<Route> cur = List.of(recommended("rt_2", RouteType.MOBILITY_FIRST_TRANSIT, 28));

        String reason = RouteStreamService.changeReason(prev, cur);

        assertThat(reason)
                .isNotNull()
                .contains("추천 경로 변경")
                .contains("TRANSIT_ONLY")
                .contains("MOBILITY_FIRST_TRANSIT");
    }

    @Test
    void 추천_경로_소요시간_2분_이상_차이나면_UPDATE() {
        List<Route> prev = List.of(recommended("rt_1", RouteType.TRANSIT_ONLY, 30));
        List<Route> cur = List.of(recommended("rt_1", RouteType.TRANSIT_ONLY, 33));

        assertThat(RouteStreamService.changeReason(prev, cur))
                .isNotNull()
                .contains("3분 변화");
    }

    @Test
    void 추천_경로_소요시간_1분_차이는_HEARTBEAT_null_반환() {
        List<Route> prev = List.of(recommended("rt_1", RouteType.TRANSIT_ONLY, 30));
        List<Route> cur = List.of(recommended("rt_1", RouteType.TRANSIT_ONLY, 31));

        assertThat(RouteStreamService.changeReason(prev, cur)).isNull();
    }

    @Test
    void 추천_경로가_사라지면_해제_이유_반환() {
        List<Route> prev = List.of(recommended("rt_1", RouteType.TRANSIT_ONLY, 30));
        List<Route> cur = List.of(notRecommended("rt_1", RouteType.TRANSIT_ONLY, 30));

        assertThat(RouteStreamService.changeReason(prev, cur))
                .isEqualTo("추천 경로 해제");
    }

    // ─── 헬퍼 ─────────────────────────────

    private static Route recommended(String id, RouteType type, int minutes) {
        return baseRoute(id, type, minutes, true);
    }

    private static Route notRecommended(String id, RouteType type, int minutes) {
        return baseRoute(id, type, minutes, false);
    }

    private static Route baseRoute(String id, RouteType type, int minutes, boolean recommended) {
        RouteCostBreakdown breakdown = new RouteCostBreakdown(
                List.of(new CostComponent("대중교통", 1650)), 1650);
        return new Route(id, type, minutes, 1650,
                breakdown, List.of(), null, 0.8, recommended,
                List.of(), new Comparison(minutes, 0), null, null);
    }
}
