package com.blackmamba.navigation.infra.adapter;

import com.blackmamba.navigation.application.route.port.MobilityTimePort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 이동수단 소요 시간 계산 어댑터.
 * 실제 자전거/킥보드 경로 API 대신 Haversine 직선거리 ÷ 평균속도로 추정.
 * 직선거리는 실제 경로보다 짧으므로 1.3 우회계수를 적용.
 */
@Component
public class MobilityTimeAdapter implements MobilityTimePort {

    // 평균속도 (km/h)
    private static final double DDAREUNGI_KMH       = 15.0;
    private static final double KICKBOARD_SHARED_KMH = 18.0;
    private static final double PERSONAL_KMH         = 20.0;

    // 직선→실제 경로 우회 계수
    private static final double DETOUR_FACTOR = 1.3;

    @Override
    public Mono<Integer> getMobilityTimeMinutes(Location origin, Location destination, MobilityType type) {
        double distKm = haversineKm(origin.lat(), origin.lng(), destination.lat(), destination.lng());
        double routeKm = distKm * DETOUR_FACTOR;
        double speedKmh = switch (type) {
            case DDAREUNGI       -> DDAREUNGI_KMH;
            case KICKBOARD_SHARED -> KICKBOARD_SHARED_KMH;
            case PERSONAL        -> PERSONAL_KMH;
        };
        int minutes = (int) Math.ceil(routeKm / speedKmh * 60);
        return Mono.just(Math.max(minutes, 1));
    }

    private double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
