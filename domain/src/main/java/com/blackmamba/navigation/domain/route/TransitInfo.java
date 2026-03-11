package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;

import java.util.List;

public record TransitInfo(
        String lineName,
        String lineColor,
        int stationCount,
        List<Location> passThroughStations  // 경유 정류장 (이름+좌표, ODsay passStopList)
) {
    /** 경유 정류장 없이 생성 (혼합 경로 추정 구간 등) */
    public static TransitInfo of(String lineName, String lineColor, int stationCount) {
        return new TransitInfo(lineName, lineColor, stationCount, List.of());
    }
}
