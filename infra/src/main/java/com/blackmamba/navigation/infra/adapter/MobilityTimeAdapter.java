package com.blackmamba.navigation.infra.adapter;

import com.blackmamba.navigation.application.route.port.MobilityTimePort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.infra.tmap.TmapPedestrianClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 이동수단 소요 시간 계산 어댑터.
 * TMAP 보행자 경로 API의 실제 도로 거리(m)를 이동수단 속도로 나눠 시간을 추정.
 * TMAP 실패 시 Haversine 직선거리 × 1.3 우회계수로 fallback.
 */
@Component
public class MobilityTimeAdapter implements MobilityTimePort {

    // 평균속도 (km/h)
    private static final double DDAREUNGI_KMH        = 15.0;
    private static final double KICKBOARD_SHARED_KMH = 18.0;
    private static final double PERSONAL_KMH         = 20.0;

    // haversine fallback 우회 계수
    private static final double DETOUR_FACTOR = 1.3;

    private final TmapPedestrianClient tmapClient;

    public MobilityTimeAdapter(TmapPedestrianClient tmapClient) {
        this.tmapClient = tmapClient;
    }

    @Override
    public Mono<Integer> getMobilityTimeMinutes(Location origin, Location destination, MobilityType type) {
        double speedKmh = speedKmh(type);
        return tmapClient.getRoadDistanceMeters(origin, destination)
                .map(opt -> {
                    double distKm = opt.isPresent()
                            ? opt.get() / 1000.0
                            : haversineKm(origin, destination) * DETOUR_FACTOR;
                    return Math.max(1, (int) Math.ceil(distKm / speedKmh * 60));
                });
    }

    private double speedKmh(MobilityType type) {
        return switch (type) {
            case DDAREUNGI        -> DDAREUNGI_KMH;
            case KICKBOARD_SHARED -> KICKBOARD_SHARED_KMH;
            case PERSONAL         -> PERSONAL_KMH;
        };
    }

    private double haversineKm(Location a, Location b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h    = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                    * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }
}
