package com.blackmamba.navigation.infra.adapter;

import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.domain.route.MobilityInfo;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.infra.ddareungi.DdareungiApiClient;
import com.blackmamba.navigation.infra.kickboard.KickboardApiClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Component
public class MobilityAvailabilityAdapter implements MobilityAvailabilityPort {

    private static final int SEARCH_RADIUS_METERS = 500;

    private final DdareungiApiClient ddareungiClient;
    private final KickboardApiClient kickboardClient;
    private final Counter ddareungiFallbackErrorCounter;
    private final Counter kickboardFallbackErrorCounter;
    private final Counter kickboardFallbackEmptyCounter;

    public MobilityAvailabilityAdapter(DdareungiApiClient ddareungiClient,
                                       KickboardApiClient kickboardClient,
                                       MeterRegistry meterRegistry) {
        this.ddareungiClient = ddareungiClient;
        this.kickboardClient = kickboardClient;
        this.ddareungiFallbackErrorCounter = meterRegistry.counter(
                "navigation.mobility.fallback.total",
                "mobility", "ddareungi",
                "reason", "error"
        );
        this.kickboardFallbackErrorCounter = meterRegistry.counter(
                "navigation.mobility.fallback.total",
                "mobility", "kickboard_shared",
                "reason", "error"
        );
        this.kickboardFallbackEmptyCounter = meterRegistry.counter(
                "navigation.mobility.fallback.total",
                "mobility", "kickboard_shared",
                "reason", "empty"
        );
    }

    @Override
    public Mono<Optional<MobilityInfo>> findNearbyMobility(double lat, double lng, MobilityType type) {
        return switch (type) {
            case DDAREUNGI      -> findNearbyDdareungi(lat, lng);
            case KICKBOARD_SHARED -> findNearbyKickboard(lat, lng);
            case PERSONAL       -> Mono.just(Optional.of(personalMobility(lat, lng)));
        };
    }

    private Mono<Optional<MobilityInfo>> findNearbyDdareungi(double lat, double lng) {
        return ddareungiClient.getNearbyStations(lat, lng, SEARCH_RADIUS_METERS)
                .map(stations -> stations.stream().findFirst()
                        .map(s -> new MobilityInfo(
                                MobilityType.DDAREUNGI,
                                "서울시 따릉이",
                                null,
                                100,
                                s.stationName(),
                                s.lat(),
                                s.lng(),
                                s.availableCount(),
                                distanceMeters(lat, lng, s.lat(), s.lng())
                        ))
                )
                .onErrorResume(ex -> {
                    ddareungiFallbackErrorCounter.increment();
                    return Mono.just(Optional.<MobilityInfo>empty());
                });
    }

    private Mono<Optional<MobilityInfo>> findNearbyKickboard(double lat, double lng) {
        return kickboardClient.getNearbyDevices(lat, lng, SEARCH_RADIUS_METERS)
                .map(devices -> {
                    if (devices.isEmpty()) {
                        kickboardFallbackEmptyCounter.increment();
                        return Optional.of(syntheticKickboard(lat, lng));
                    }
                    return devices.stream().findFirst()
                            .map(d -> new MobilityInfo(
                                    MobilityType.KICKBOARD_SHARED,
                                    d.operatorName(),
                                    d.deviceId(),
                                    d.batteryLevel(),
                                    null,
                                    d.lat(),
                                    d.lng(),
                                    1,
                                    distanceMeters(lat, lng, d.lat(), d.lng())
                            ));
                })
                .onErrorResume(ex -> {
                    kickboardFallbackErrorCounter.increment();
                    return Mono.just(Optional.of(syntheticKickboard(lat, lng)));
                });
    }

    private MobilityInfo personalMobility(double lat, double lng) {
        return new MobilityInfo(
                MobilityType.PERSONAL,
                "개인",
                null,
                100,
                null,
                lat,
                lng,
                1,
                0
        );
    }

    private MobilityInfo syntheticKickboard(double lat, double lng) {
        return new MobilityInfo(
                MobilityType.KICKBOARD_SHARED,
                "공유 킥보드(추정)",
                null,
                0,
                null,
                lat,
                lng,
                1,
                0
        );
    }

    private int distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return (int) (6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }
}
