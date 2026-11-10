package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.evaluation.Comparison;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.route.evaluation.RouteInsightFactory;
import com.blackmamba.navigation.domain.route.evaluation.RouteScoreCalculator;
import org.springframework.stereotype.Component;

@Component
public class RouteEvaluator {

    private final RouteScoreCalculator routeScoreCalculator;
    private final RouteInsightFactory routeInsightFactory;

    public RouteEvaluator(RouteScoreCalculator routeScoreCalculator,
                          RouteInsightFactory routeInsightFactory) {
        this.routeScoreCalculator = routeScoreCalculator;
        this.routeInsightFactory = routeInsightFactory;
    }

    public Route evaluate(Route route,
                          Route baselineRoute,
                          int baselineMinutes,
                          boolean recommended,
                          RecommendationPreference preference) {
        int savedMinutes = Math.max(baselineMinutes - route.totalMinutes(), 0);
        Route compared = route.withComparison(new Comparison(baselineMinutes, savedMinutes));
        var evaluation = routeScoreCalculator.evaluate(compared, preference);
        Route scored = compared.withEvaluation(evaluation)
                .withScore(evaluation.totalScore(), recommended);
        return routeInsightFactory.enrich(scored, baselineRoute, preference);
    }

    /**
     * 점수는 그대로 두고 추천 여부만 바꾼다.
     * 추천 근거·배지 문구는 추천 여부를 읽어 만들어지므로 플래그만 뒤집으면 문구가 어긋난다 — 함께 다시 만든다.
     */
    public Route reRecommend(Route route, Route baselineRoute, boolean recommended, RecommendationPreference preference) {
        return routeInsightFactory.enrich(route.withScore(route.score(), recommended), baselineRoute, preference);
    }

    public Route evaluate(Route route, boolean recommended, RecommendationPreference preference) {
        var evaluation = routeScoreCalculator.evaluate(route, preference);
        Route scored = route.withEvaluation(evaluation)
                .withScore(evaluation.totalScore(), recommended);
        return routeInsightFactory.enrich(scored, route, preference);
    }
}
