package com.blackmamba.navigation.api.nlp;

import com.blackmamba.navigation.api.place.PlaceResponse;
import com.blackmamba.navigation.application.route.RouteIntent;
import com.blackmamba.navigation.domain.route.Route;

import java.util.List;

/** {@code GET /api/nlp/routes} 응답 — 파싱 결과와 해석된 좌표를 경로와 함께 돌려준다. */
public record NaturalLanguageRoutesResponse(
        String query,
        RouteIntent parsedIntent,
        PlaceResponse origin,
        PlaceResponse destination,
        String searchMode,
        String preference,
        List<Route> routes
) {
}
