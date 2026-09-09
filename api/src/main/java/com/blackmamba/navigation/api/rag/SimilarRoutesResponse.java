package com.blackmamba.navigation.api.rag;

import com.blackmamba.navigation.api.place.PlaceResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** {@code GET /api/rag/similar-routes} 응답 봉투 — 적용된 필터를 함께 돌려준다. */
public record SimilarRoutesResponse(
        String query,
        int topK,
        double threshold,
        AppliedFilter filter,
        int hitCount,
        List<SimilarRouteResponse> results
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppliedFilter(
            PlaceResponse origin,
            PlaceResponse destination,
            List<String> mobility,
            boolean applied
    ) {
    }
}
