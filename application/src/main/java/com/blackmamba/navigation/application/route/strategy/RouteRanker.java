package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.RouteEvaluator;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.evaluation.GenerationDiagnostic;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.route.evaluation.RouteInsights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 후보 경로를 평가·정렬해 상위 N개를 고르고, 1위에 추천 표시를 붙인다.
 * <p>
 * 1위는 {@code recommended=true} 로 재평가한다 — 추천 여부에 따라
 * insight/근거 문구가 달라지므로 정렬 후 한 번 더 평가해야 한다.
 */
final class RouteRanker {

    private static final int MAX_RESULTS = 5;

    private final RouteEvaluator routeEvaluator;

    RouteRanker(RouteEvaluator routeEvaluator) {
        this.routeEvaluator = routeEvaluator;
    }

    /**
     * @param candidates  기준 경로를 포함한 전체 후보
     * @param baseRoute   대중교통 기준 경로 (절감 비교 기준)
     * @param baseMinutes 기준 소요시간
     */
    List<Route> rank(List<Route> candidates, Route baseRoute, int baseMinutes, RecommendationPreference preference) {
        List<Route> evaluated = candidates.stream()
                .map(route -> routeEvaluator.evaluate(route, baseRoute, baseMinutes, false, preference))
                .sorted(Comparator.comparingDouble(Route::score).reversed())
                .limit(MAX_RESULTS)
                .toList();

        if (evaluated.isEmpty()) {
            return evaluated;
        }

        List<Route> result = new ArrayList<>(evaluated);
        Route top = result.getFirst();
        result.set(0, routeEvaluator.evaluate(top, baseRoute, baseMinutes, true, preference));
        return result;
    }

    static Route withDiagnostics(Route route, List<GenerationDiagnostic> diagnostics) {
        return route.withInsights(new RouteInsights(List.of(), List.of(), diagnostics, List.of()));
    }
}
