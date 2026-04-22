package com.blackmamba.navigation.api.rag;

import com.blackmamba.navigation.application.route.port.ScoredRouteHistoryEntry;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.RouteHistoryEntry;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/rag/similar-routes} 의 결과 항목 DTO.
 * <p>
 * 도메인({@link RouteHistoryEntry})의 내부 표현 대신 "클라이언트가 읽기 좋은" 형태로 재구성.
 * - description 은 임베딩 소스와 동일해 "왜 이 경로가 유사하다고 판단됐는지" 근거로 쓰기 좋음
 * - similarityScore 는 0~1 (코사인 유사도, bge-m3 정규화 벡터 기준)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimilarRouteResponse(
        String routeId,
        double similarityScore,
        String description,
        Location origin,
        Location destination,
        String originGeohash,
        String destinationGeohash,
        String preference,
        String routeType,
        int totalMinutes,
        int totalCostWon,
        List<MobilityType> mobilityTypes,
        Instant createdAt
) {
    public static SimilarRouteResponse from(ScoredRouteHistoryEntry scored) {
        RouteHistoryEntry e = scored.entry();
        return new SimilarRouteResponse(
                e.routeId(),
                round4(scored.similarityScore()),
                e.description(),
                e.origin(),
                e.destination(),
                e.originGeohash(),
                e.destinationGeohash(),
                e.preference(),
                e.routeType() == null ? null : e.routeType().name(),
                e.totalMinutes(),
                e.totalCostWon(),
                e.mobilityTypes(),
                e.createdAt()
        );
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
