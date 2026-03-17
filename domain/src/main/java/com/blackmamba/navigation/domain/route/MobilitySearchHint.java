package com.blackmamba.navigation.domain.route;

public record MobilitySearchHint(
        String stationName,
        String stationId,
        int availableCount,
        int rackTotalCount,
        int distanceMeters,
        boolean dropoff
) {
    public String toDiagnosticSuffix() {
        String direction = dropoff ? "최근접 반납 후보" : "최근접 대여 후보";
        return direction + "는 " + stationName
                + "(" + distanceMeters + "m, 대여가능 " + availableCount + "대, 거치대 " + rackTotalCount + "칸)입니다.";
    }
}
