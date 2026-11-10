package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;

import java.util.Comparator;
import java.util.List;

/**
 * 전체 정류소 snapshot 에서 반경 내 정류소를 <b>가까운 순</b>으로 고른다.
 * <p>
 * 호출자들은 결과의 첫 번째를 "최근접 정류소" 로 쓴다 (대여 지점, 반납 지점, 허브 힌트).
 * 정렬 없이 API 응답 순서 그대로 넘기면 650m 정류소가 80m 정류소보다 먼저 와서
 * 접근 도보·대여소 접근 페널티·허브 우선순위가 전부 무작위가 된다.
 * <p>
 * 요청 한 번에 허브 수십 곳에서 불리고 매번 서울 전체(약 2,700개)를 훑는다.
 * 위경도 상자로 먼저 걸러 대부분을 삼각함수 없이 탈락시키고, 거리는 정류소당 한 번만 계산한다.
 */
public class DdareungiStationFilter {

    private static final double METERS_PER_LATITUDE_DEGREE = 111_000.0;
    /** 상자는 원보다 넉넉해야 한다 — 위도별 경도 길이 근사 오차를 흡수 */
    private static final double BOUNDING_BOX_MARGIN = 1.1;

    public List<DdareungiStation> filterNearby(List<DdareungiStation> stations,
                                                double lat, double lng, int radiusMeters) {
        return filterNearby(stations, lat, lng, radiusMeters, true);
    }

    public List<DdareungiStation> filterNearby(List<DdareungiStation> stations,
                                               double lat, double lng, int radiusMeters,
                                               boolean requireAvailableBike) {
        double latDelta = radiusMeters * BOUNDING_BOX_MARGIN / METERS_PER_LATITUDE_DEGREE;
        double lngDelta = latDelta / Math.max(Math.cos(Math.toRadians(lat)), 0.01);
        return stations.stream()
                .filter(station -> !requireAvailableBike || station.availableCount() > 0)
                .filter(station -> Math.abs(station.lat() - lat) <= latDelta && Math.abs(station.lng() - lng) <= lngDelta)
                .map(station -> new Candidate(station, GeoDistance.meters(lat, lng, station.lat(), station.lng())))
                .filter(candidate -> candidate.distanceMeters() <= radiusMeters)
                .sorted(Comparator.comparingDouble(Candidate::distanceMeters))
                .map(Candidate::station)
                .toList();
    }

    private record Candidate(DdareungiStation station, double distanceMeters) {}
}
