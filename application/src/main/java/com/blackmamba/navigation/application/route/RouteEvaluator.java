package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.Comparison;
import com.blackmamba.navigation.domain.route.Route;
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

    public Route evaluate(Route route, Route baselineRoute, int baselineMinutes, boolean recommended) {
        int savedMinutes = Math.max(baselineMinutes - route.totalMinutes(), 0);
        Route compared = route.withComparison(new Comparison(baselineMinutes, savedMinutes));
        var evaluation = routeScoreCalculator.evaluate(compared);
        Route scored = compared.withEvaluation(evaluation)
                .withScore(evaluation.totalScore(), recommended);
        return routeInsightFactory.enrich(scored, baselineRoute);
    }

    public Route evaluate(Route route, boolean recommended) {
        var evaluation = routeScoreCalculator.evaluate(route);
        Route scored = route.withEvaluation(evaluation)
                .withScore(evaluation.totalScore(), recommended);
        return routeInsightFactory.enrich(scored, route);
    }
}
