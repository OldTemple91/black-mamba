package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;

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
) {}
