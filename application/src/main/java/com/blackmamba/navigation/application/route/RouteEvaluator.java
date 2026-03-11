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
        Route scored = compared.withScore(routeScoreCalculator.calculate(compared), recommended);
        return routeInsightFactory.enrich(scored, baselineRoute);
    }

    public Route evaluate(Route route, boolean recommended) {
        Route scored = route.withScore(routeScoreCalculator.calculate(route), recommended);
        return routeInsightFactory.enrich(scored, route);
    }
}
