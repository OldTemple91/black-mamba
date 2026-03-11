package com.blackmamba.navigation.infra.adapter;

import com.blackmamba.navigation.application.route.port.MobilityTimePort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityRouteResult;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.infra.tmap.TmapPedestrianClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 이동수단 경로 어댑터.
 * TMAP 보행자 API의 실제 도로 거리(m)로 소요 시간을 추정하고,
 * GeoJSON 경로 좌표를 함께 반환해 지도에 실제 도로 경로를 그릴 수 있게 한다.
 * TMAP 실패 시 Haversine 직선거리 × 1.3 우회계수로 fallback (좌표는 빈 리스트).
 */
@Component
public class MobilityTimeAdapter implements MobilityTimePort {

    private static final double WALKING_KMH          = 4.5;
    private static final double DDAREUNGI_KMH        = 15.0;
    private static final double KICKBOARD_SHARED_KMH = 18.0;
    private static final double PERSONAL_KMH         = 20.0;
    private static final double DETOUR_FACTOR        = 1.3;

    private final TmapPedestrianClient tmapClient;

    public MobilityTimeAdapter(TmapPedestrianClient tmapClient) {
        this.tmapClient = tmapClient;
    }

    @Override
    public Mono<MobilityRouteResult> getMobilityRoute(Location origin, Location destination, MobilityType type) {
        return getRoute(origin, destination, speedKmh(type));
    }

    @Override
    public Mono<MobilityRouteResult> getWalkingRoute(Location origin, Location destination) {
        return getRoute(origin, destination, WALKING_KMH);
    }

    private Mono<MobilityRouteResult> getRoute(Location origin, Location destination, double speedKmh) {
        return tmapClient.getRoute(origin, destination)
                .map(opt -> {
                    if (opt.isPresent()) {
                        TmapPedestrianClient.TmapRouteData data = opt.get();
                        double distKm = data.distanceMeters() / 1000.0;
                        int minutes = Math.max(1, (int) Math.ceil(distKm / speedKmh * 60));
                        return new MobilityRouteResult(minutes, data.distanceMeters(), data.coordinates());
                    }
                    // haversine fallback: 좌표 없이 시간만 반환
                    double distKm = haversineKm(origin, destination) * DETOUR_FACTOR;
                    int minutes = Math.max(1, (int) Math.ceil(distKm / speedKmh * 60));
                    int distanceMeters = (int) Math.ceil(distKm * 1000);
                    return new MobilityRouteResult(minutes, distanceMeters, List.of());
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
