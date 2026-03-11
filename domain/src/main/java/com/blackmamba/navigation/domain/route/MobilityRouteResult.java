package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;

import java.util.List;

/**
 * 이동수단 경로 조회 결과.
 * TMAP 보행자 API로부터 소요 시간(분)과 실제 도로 경로 좌표를 함께 반환.
 *
 * @param durationMinutes   이동 소요 시간 (분)
 * @param distanceMeters    실제 도로 거리 (m)
 * @param routeCoordinates  실제 도로 경로 좌표 목록 (비어있으면 직선 fallback)
 */
public record MobilityRouteResult(int durationMinutes, int distanceMeters, List<Location> routeCoordinates) {

    /** 좌표 없이 시간만 있는 fallback 결과 */
    public static MobilityRouteResult timeOnly(int durationMinutes) {
        return new MobilityRouteResult(durationMinutes, 0, List.of());
    }
}
