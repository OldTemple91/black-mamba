package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record Route(
        String routeId,
        RouteType type,
        int totalMinutes,
        int totalCostWon,
        RouteCostBreakdown costBreakdown,
        List<RouteHub> selectedHubs,
        RouteEvaluation evaluation,
        double score,
        boolean recommended,
        List<Leg> legs,
        Comparison comparison,
        RouteInsights insights,
        RouteComparison carComparison,      // F-1: 자가용 대비 비교 (nullable)
        CarbonSummary carbon                 // C-2: 경로별 탄소 배출량 (nullable = 미계산)
) {
    // compact constructor: 모든 생성 경로에서 불변 컬렉션 강제
    public Route {
        legs = List.copyOf(normalizeLegs(legs));
        selectedHubs = selectedHubs == null ? List.of() : List.copyOf(selectedHubs);
    }

    public static Route of(List<Leg> legs, RouteType type) {
        List<Leg> normalizedLegs = normalizeLegs(legs);
        int total = normalizedLegs.stream().mapToInt(Leg::durationMinutes).sum();
        RouteCostBreakdown costBreakdown = RouteCostEstimator.estimate(normalizedLegs);
        return new Route(
                deterministicRouteId(type, normalizedLegs, total),
                type, total, costBreakdown.totalWon(), costBreakdown, List.of(), null, 0.0, false, normalizedLegs, null, null, null, null
        );
    }

    /**
     * 경로 내용 기반의 결정론적 ID 생성.
     * 같은 경로(type + leg 구성 + 총 시간)면 같은 UUID, 달라지면 다른 UUID.
     *
     * <p>SSE 변화 감지가 의미를 가지려면 ID가 내용에 결정되어야 한다.
     * 무작위 UUID였다면 매 호출마다 다른 ID가 나와 "진짜 변화"와
     * "ID만 다른 가짜 변화"를 구분할 수 없다.
     */
    private static String deterministicRouteId(RouteType type, List<Leg> legs, int totalMinutes) {
        StringBuilder sb = new StringBuilder();
        sb.append(type == null ? "NULL" : type.name()).append('|');
        sb.append(totalMinutes).append('|');
        for (Leg leg : legs) {
            sb.append(leg.type() == null ? "" : leg.type().name()).append(':');
            sb.append(leg.mode() == null ? "" : leg.mode()).append(':');
            sb.append(leg.durationMinutes()).append(':');
            sb.append(leg.start() == null ? "" : safe(leg.start().name())).append('>');
            sb.append(leg.end() == null ? "" : safe(leg.end().name())).append('|');
        }
        return UUID.nameUUIDFromBytes(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    public Route withComparison(Comparison comparison) {
        return new Route(routeId, type, totalMinutes, totalCostWon, costBreakdown, selectedHubs, evaluation, score, recommended, legs, comparison, insights, carComparison, carbon);
    }

    public Route withScore(double score, boolean recommended) {
        return new Route(routeId, type, totalMinutes, totalCostWon, costBreakdown, selectedHubs, evaluation, score, recommended, legs, comparison, insights, carComparison, carbon);
    }

    public Route withInsights(RouteInsights insights) {
        return new Route(routeId, type, totalMinutes, totalCostWon, costBreakdown, selectedHubs, evaluation, score, recommended, legs, comparison, insights, carComparison, carbon);
    }

    public Route withEvaluation(RouteEvaluation evaluation) {
        return new Route(routeId, type, totalMinutes, totalCostWon, costBreakdown, selectedHubs, evaluation, score, recommended, legs, comparison, insights, carComparison, carbon);
    }

    public Route withSelectedHubs(List<RouteHub> selectedHubs) {
        return new Route(routeId, type, totalMinutes, totalCostWon, costBreakdown, selectedHubs, evaluation, score, recommended, legs, comparison, insights, carComparison, carbon);
    }

    public Route withCarComparison(RouteComparison carComparison) {
        return new Route(routeId, type, totalMinutes, totalCostWon, costBreakdown, selectedHubs, evaluation, score, recommended, legs, comparison, insights, carComparison, carbon);
    }

    /** C-2: 탄소 요약 첨부. 기존 다른 필드는 유지. */
    public Route withCarbon(CarbonSummary carbon) {
        return new Route(routeId, type, totalMinutes, totalCostWon, costBreakdown, selectedHubs, evaluation, score, recommended, legs, comparison, insights, carComparison, carbon);
    }

    private static List<Leg> normalizeLegs(List<Leg> legs) {
        List<Leg> normalized = new ArrayList<>();
        for (Leg leg : legs) {
            if (!normalized.isEmpty()
                    && normalized.getLast().type() == LegType.WALK
                    && leg.type() == LegType.WALK) {
                normalized.set(normalized.size() - 1, mergeWalkLegs(normalized.getLast(), leg));
                continue;
            }
            normalized.add(leg);
        }
        return normalized;
    }

    private static Leg mergeWalkLegs(Leg first, Leg second) {
        List<Location> mergedCoordinates = new ArrayList<>();
        if (first.routeCoordinates() != null) mergedCoordinates.addAll(first.routeCoordinates());
        if (second.routeCoordinates() != null) {
            if (!mergedCoordinates.isEmpty() && !second.routeCoordinates().isEmpty()) {
                Location last = mergedCoordinates.getLast();
                Location next = second.routeCoordinates().getFirst();
                if (samePoint(last, next)) {
                    mergedCoordinates.addAll(second.routeCoordinates().subList(1, second.routeCoordinates().size()));
                } else {
                    mergedCoordinates.addAll(second.routeCoordinates());
                }
            } else {
                mergedCoordinates.addAll(second.routeCoordinates());
            }
        }

        return new Leg(
                LegType.WALK,
                "WALK",
                first.durationMinutes() + second.durationMinutes(),
                first.distanceMeters() + second.distanceMeters(),
                first.start(),
                second.end(),
                null,
                null,
                mergedCoordinates.isEmpty() ? null : List.copyOf(mergedCoordinates)
        );
    }

    private static boolean samePoint(Location a, Location b) {
        return a != null && b != null
                && Double.compare(a.lat(), b.lat()) == 0
                && Double.compare(a.lng(), b.lng()) == 0;
    }
}
