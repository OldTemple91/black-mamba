package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;

import java.util.List;

public class DdareungiStationFilter {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

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
                .toList();
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
