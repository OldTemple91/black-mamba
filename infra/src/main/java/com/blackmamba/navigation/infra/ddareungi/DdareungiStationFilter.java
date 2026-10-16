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
 */
public class DdareungiStationFilter {

    public List<DdareungiStation> filterNearby(List<DdareungiStation> stations,
                                                double lat, double lng, int radiusMeters) {
        return filterNearby(stations, lat, lng, radiusMeters, true);
    }

    public List<DdareungiStation> filterNearby(List<DdareungiStation> stations,
                                               double lat, double lng, int radiusMeters,
                                               boolean requireAvailableBike) {
        return stations.stream()
                .filter(s -> !requireAvailableBike || s.availableCount() > 0)
                .filter(s -> distanceMeters(lat, lng, s.lat(), s.lng()) <= radiusMeters)
                .sorted(Comparator.comparingDouble(s -> distanceMeters(lat, lng, s.lat(), s.lng())))
                .toList();
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        return GeoDistance.meters(lat1, lng1, lat2, lng2);
    }
}
