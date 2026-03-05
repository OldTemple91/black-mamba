package com.blackmamba.navigation.infra.kickboard;

import com.blackmamba.navigation.infra.kickboard.dto.KickboardDevice;
import java.util.List;

public class KickboardDeviceFilter {

    private static final int MIN_BATTERY = 20; // 배터리 20% 미만 제외
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    public List<KickboardDevice> filterNearby(List<KickboardDevice> devices,
                                               double lat, double lng, int radiusMeters) {
        return devices.stream()
                .filter(d -> d.batteryLevel() >= MIN_BATTERY)
                .filter(d -> distanceMeters(lat, lng, d.lat(), d.lng()) <= radiusMeters)
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
