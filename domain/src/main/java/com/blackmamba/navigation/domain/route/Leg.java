package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.mobility.MobilityInfo;

import java.util.List;

public record Leg(
        LegType type,
        String mode,
        int durationMinutes,
        int distanceMeters,
        Location start,
        Location end,
        TransitInfo transitInfo,
        MobilityInfo mobilityInfo,
        List<Location> routeCoordinates   // 실제 도로 경로 좌표 (BIKE/KICKBOARD), null 이면 직선 표시
) {
    /**
     * 실시간 대중교통 경로를 얻지 못해 기준 경로·직선거리로 근사한 TRANSIT 구간의 mode 값.
     * 실제 조회 구간은 mode 가 "SUBWAY"/"BUS" 다.
     */
    public static final String ESTIMATED_TRANSIT_MODE = "대중교통";

    public boolean isEstimatedTransit() {
        return type == LegType.TRANSIT && ESTIMATED_TRANSIT_MODE.equals(mode);
    }
}
