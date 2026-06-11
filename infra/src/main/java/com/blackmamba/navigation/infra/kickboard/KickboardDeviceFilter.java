package com.blackmamba.navigation.infra.kickboard;

import com.blackmamba.navigation.domain.location.GeoDistance;

import com.blackmamba.navigation.infra.kickboard.dto.KickboardDevice;
import java.util.List;

public class KickboardDeviceFilter {

    private static final int MIN_BATTERY = 20; // 배터리 20% 미만 제외

    public List<KickboardDevice> filterNearby(List<KickboardDevice> devices,
                                               double lat, double lng, int radiusMeters) {
        return devices.stream()
                .filter(d -> d.batteryLevel() >= MIN_BATTERY)
                .filter(d -> distanceMeters(lat, lng, d.lat(), d.lng()) <= radiusMeters)
                .toList();
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        return GeoDistance.meters(lat1, lng1, lat2, lng2);
    }
}
