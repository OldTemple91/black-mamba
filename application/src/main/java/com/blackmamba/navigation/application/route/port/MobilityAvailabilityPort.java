package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.route.MobilityInfo;
import com.blackmamba.navigation.domain.route.MobilitySearchHint;
import com.blackmamba.navigation.domain.route.MobilityType;
import reactor.core.publisher.Mono;

import java.util.Optional;

public interface MobilityAvailabilityPort {
    Mono<Optional<MobilityInfo>> findNearbyMobility(double lat, double lng, MobilityType type);
    Mono<Optional<MobilityInfo>> findNearbyDropoff(double lat, double lng, MobilityType type);
    Mono<Optional<MobilityInfo>> findSegmentMobility(double startLat, double startLng, double endLat, double endLng, MobilityType type);
    Mono<Optional<MobilitySearchHint>> findNearestMobilityHint(double lat, double lng, MobilityType type, boolean dropoff);
}
