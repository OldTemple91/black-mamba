package com.blackmamba.navigation.infra.adapter;

import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.domain.route.mobility.SegmentAvailability;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.application.route.segment.SegmentAvailabilityChecker;
import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.route.mobility.AvailabilitySource;
import com.blackmamba.navigation.domain.route.mobility.MobilityInfo;
import com.blackmamba.navigation.domain.route.mobility.MobilitySearchHint;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import com.blackmamba.navigation.infra.common.cache.TtlCache;
import com.blackmamba.navigation.infra.common.cache.TtlCacheRegistry;
import com.blackmamba.navigation.infra.ddareungi.DdareungiApiClient;
import com.blackmamba.navigation.infra.ddareungi.DdareungiApiClient.StationSnapshot;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import com.blackmamba.navigation.infra.kickboard.KickboardApiClient;
import com.blackmamba.navigation.infra.kickboard.dto.KickboardDevice;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Component
public class MobilityAvailabilityAdapter implements MobilityAvailabilityPort {

    private static final Logger log = LoggerFactory.getLogger(MobilityAvailabilityAdapter.class);
    private static final String DDAREUNGI_OPERATOR = "서울시 따릉이";
    private static final String SYNTHETIC_KICKBOARD_OPERATOR = "공유 킥보드(추정)";
    /** 실 배터리 미확인 시 표시할 기본값 (B-4: battery=0 노출 방지) */
    private static final int SYNTHETIC_KICKBOARD_BATTERY = 50;
    private static final int MIN_HINT_RADIUS_METERS = 1_400;

    private final DdareungiApiClient ddareungiClient;
    private final KickboardApiClient kickboardClient;
    private final Map<MobilityType, Counter> typeToFallbackErrorCounter = new EnumMap<>(MobilityType.class);
    private final Counter kickboardFallbackEmptyCounter;
    private final TtlCache<AvailabilityKey, Lookup> availabilityCache;
    private final int searchRadiusMeters;
    private final boolean tagoEnabled;

    public MobilityAvailabilityAdapter(DdareungiApiClient ddareungiClient,
                                       KickboardApiClient kickboardClient,
                                       @Value("${navigation.mobility.search-radius-meters:700}") int searchRadiusMeters,
                                       @Value("${navigation.cache.mobility-availability-ttl-ms:20000}") long availabilityCacheTtlMs,
                                       @Value("${tago.enabled:false}") boolean tagoEnabled,
                                       MeterRegistry meterRegistry,
                                       TtlCacheRegistry cacheRegistry) {
        this.ddareungiClient = ddareungiClient;
        this.kickboardClient = kickboardClient;
        this.searchRadiusMeters = searchRadiusMeters;
        this.tagoEnabled = tagoEnabled;
        this.availabilityCache = cacheRegistry.create("mobility_availability", availabilityCacheTtlMs);
        // 외부 조회가 있는 공유 수단만 — 개인 수단은 조회 자체가 없어 오류 시리즈가 의미 없다
        for (MobilityType type : MobilityType.values()) {
            if (!type.isShared()) {
                continue;
            }
            typeToFallbackErrorCounter.put(type, meterRegistry.counter(
                    "navigation.mobility.fallback.total",
                    "mobility", type.name().toLowerCase(Locale.ROOT),
                    "reason", "error"
            ));
        }
        this.kickboardFallbackEmptyCounter = meterRegistry.counter(
                "navigation.mobility.fallback.total",
                "mobility", "kickboard_shared",
                "reason", "empty"
        );
    }

    @Override
    public Mono<Optional<MobilityInfo>> findNearbyMobility(double lat, double lng, MobilityType type) {
        return cachedLookup(lat, lng, type, false, () -> switch (type) {
            case DDAREUNGI -> findNearbyDdareungi(lat, lng);
            case KICKBOARD_SHARED -> findNearbyKickboard(lat, lng);
            case PERSONAL_EBIKE -> Mono.just(Optional.of(personalMobility(lat, lng, MobilityType.PERSONAL_EBIKE)));
            case PERSONAL_KICKBOARD -> Mono.just(Optional.of(personalMobility(lat, lng, MobilityType.PERSONAL_KICKBOARD)));
        });
    }

    @Override
    public Mono<Optional<MobilityInfo>> findNearbyDropoff(double lat, double lng, MobilityType type) {
        return cachedLookup(lat, lng, type, true, () -> switch (type) {
            case DDAREUNGI -> nearbyStations(lat, lng, searchRadiusMeters, false)
                    .map(snapshot -> snapshot.stations().stream().findFirst()
                            .map(station -> ddareungiInfo(station, distanceMeters(lat, lng, station.lat(), station.lng()), snapshot.source())));
            case KICKBOARD_SHARED, PERSONAL_EBIKE, PERSONAL_KICKBOARD -> findNearbyMobility(lat, lng, type);
        });
    }

    /**
     * 성립 규칙(대여 있음 AND 반납 있음 AND 서로 다른 정류소)은 도메인의 {@link SegmentAvailability} 가 판정한다 —
     * 어댑터는 조회만 한다. 세그먼트 단위 캐시는 두지 않는다: 입력인 대여·반납 조회가 각각 캐시되고 조합 비용은 없다.
     */
    @Override
    public Mono<MobilityInfo> findSegmentMobility(double startLat, double startLng, double endLat, double endLng, MobilityType type) {
        return SegmentAvailabilityChecker.check(this, new Location(null, startLat, startLng), new Location(null, endLat, endLng), type)
                .flatMap(availability -> Mono.justOrEmpty(availability.findInfo()));
    }

    @Override
    public Mono<Optional<MobilitySearchHint>> findNearestMobilityHint(double lat, double lng, MobilityType type, boolean dropoff) {
        if (type != MobilityType.DDAREUNGI) {
            return Mono.just(Optional.empty());
        }

        int hintRadiusMeters = Math.max(searchRadiusMeters * 2, MIN_HINT_RADIUS_METERS);
        // 대여 힌트는 "자전거가 있는" 정류소여야 한다 — 재고 0대 정류소를 가까운 대여 후보로 안내하지 않는다
        boolean requireAvailableBike = !dropoff;
        return ddareungiClient.getNearbyStations(lat, lng, hintRadiusMeters, requireAvailableBike)
                .map(snapshot -> snapshot.stations().stream().findFirst()
                        .map(station -> new MobilitySearchHint(
                                station.stationName(),
                                station.stationId(),
                                station.availableCount(),
                                station.rackTotalCount(),
                                distanceMeters(lat, lng, station.lat(), station.lng()),
                                dropoff
                        )))
                .onErrorResume(ex -> {
                    log.warn("[따릉이] 최근접 정류소 힌트 조회 실패 → 진단 문구에 힌트 생략: {}", ex.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    /**
     * 조회 오류는 호출자에게 "후보 없음" 으로 답하되 캐시에는 남기지 않는다 —
     * 일시 장애 한 번이 TTL 동안 그 좌표의 모든 허브를 NO_PICKUP 으로 고정하지 않게.
     */
    private Mono<Optional<MobilityInfo>> cachedLookup(double lat, double lng, MobilityType type, boolean dropoff,
                                                      Supplier<Mono<Optional<MobilityInfo>>> loaderSupplier) {
        AvailabilityKey key = AvailabilityKey.of(lat, lng, type, dropoff);
        Supplier<Mono<Lookup>> loader = () -> loaderSupplier.get()
                .map(Lookup::found)
                .onErrorResume(ex -> {
                    log.warn("[가용성] {} 조회 오류 → 후보 없음 처리(캐시 제외): {}", type, ex.getMessage());
                    Counter errorCounter = typeToFallbackErrorCounter.get(type);
                    if (errorCounter != null) {
                        errorCounter.increment();
                    }
                    return Mono.just(Lookup.failure());
                });
        return availabilityCache.get(key, loader, Lookup::isCacheable)
                .map(Lookup::info);
    }

    /** 캐시 단위 조회 결과 — 오류 폴백은 값은 비었지만 캐시 자격이 없다. */
    private record Lookup(Optional<MobilityInfo> info, boolean failed) {
        private static Lookup found(Optional<MobilityInfo> info) {
            return new Lookup(info, false);
        }

        private static Lookup failure() {
            return new Lookup(Optional.empty(), true);
        }

        private boolean isCacheable() {
            return !failed;
        }
    }

    /**
     * 따릉이 클라이언트는 장애 때 예외 대신 출처 EMPTY 인 빈 snapshot 을 준다.
     * 그대로 두면 "반경 내 정류소 없음" 으로 캐시되므로, 여기서 오류로 바꿔 캐시 제외·오류 카운터 경로에 태운다.
     */
    private Mono<StationSnapshot> nearbyStations(double lat, double lng, int radiusMeters, boolean requireAvailableBike) {
        return ddareungiClient.getNearbyStations(lat, lng, radiusMeters, requireAvailableBike)
                .<StationSnapshot>handle((snapshot, sink) -> {
                    if (snapshot.isUnavailable()) {
                        sink.error(new IllegalStateException("따릉이 snapshot 을 확보하지 못함"));
                        return;
                    }
                    sink.next(snapshot);
                });
    }

    private Mono<Optional<MobilityInfo>> findNearbyDdareungi(double lat, double lng) {
        return nearbyStations(lat, lng, searchRadiusMeters, true)
                .map(snapshot -> {
                    log.debug("[따릉이] 검색 lat={}, lng={}, 반경={}m → 반경 내 대여가능 정류소 {}개",
                            lat, lng, searchRadiusMeters, snapshot.stations().size());
                    Optional<MobilityInfo> result = snapshot.stations().stream().findFirst()
                            .map(station -> {
                                int dist = distanceMeters(lat, lng, station.lat(), station.lng());
                                log.debug("[따릉이] 선택: {} | 대여가능 {}대 | 거리 {}m",
                                        station.stationName(), station.availableCount(), dist);
                                return ddareungiInfo(station, dist, snapshot.source());
                            });
                    if (result.isEmpty()) {
                        log.debug("[따릉이] 반경 내 대여 가능한 정류소 없음");
                    }
                    return result;
                });
    }

    private Mono<Optional<MobilityInfo>> findNearbyKickboard(double lat, double lng) {
        // TAGO API 비활성화 설정 시 외부 호출 스킵하고 synthetic 폴백으로 즉시 반환.
        // 현재(2026-04) 서울 데이터 미제공 상태이므로 기본값 false. 서울 제공 시 yml 한 줄로 복구.
        if (!tagoEnabled) {
            kickboardFallbackEmptyCounter.increment();
            log.debug("[킥보드] TAGO 비활성화(tago.enabled=false) → synthetic 폴백 (호출 스킵)");
            return Mono.just(Optional.of(syntheticKickboard(lat, lng)));
        }
        return kickboardClient.getNearbyDevices(lat, lng, searchRadiusMeters)
                .map(devices -> {
                    log.info("[킥보드] 검색 lat={}, lng={}, 반경={}m → 반경 내 배터리≥20% 기기 {}개",
                            lat, lng, searchRadiusMeters, devices.size());
                    if (devices.isEmpty()) {
                        kickboardFallbackEmptyCounter.increment();
                        log.info("[킥보드] 반경 내 기기 없음 → 가상 킥보드(추정)로 폴백");
                        return Optional.of(syntheticKickboard(lat, lng));
                    }
                    return devices.stream().findFirst()
                            .map(device -> {
                                int dist = distanceMeters(lat, lng, device.lat(), device.lng());
                                log.info("[킥보드] 선택: {} | 기기 ID {} | 배터리 {}% | 거리 {}m",
                                        device.operatorName(), device.deviceId(), device.batteryLevel(), dist);
                                return sharedKickboard(device, dist);
                            });
                })
                .onErrorResume(ex -> {
                    log.warn("[킥보드] API 오류: {} → 가상 킥보드(추정)로 폴백", ex.getMessage());
                    typeToFallbackErrorCounter.get(MobilityType.KICKBOARD_SHARED).increment();
                    return Mono.just(Optional.of(syntheticKickboard(lat, lng)));
                });
    }

    // ── MobilityInfo 조립 ─────────────────────────────────────────────────

    private static MobilityInfo ddareungiInfo(DdareungiStation station, int distanceMeters, AvailabilitySource source) {
        return new MobilityInfo(
                MobilityType.DDAREUNGI,
                DDAREUNGI_OPERATOR,
                null,
                100,
                station.stationId(),
                station.stationName(),
                station.rackTotalCount(),
                station.lat(),
                station.lng(),
                station.availableCount(),
                distanceMeters,
                null,
                null,
                0.0,
                0.0,
                source
        );
    }

    private static MobilityInfo sharedKickboard(KickboardDevice device, int distanceMeters) {
        return new MobilityInfo(
                MobilityType.KICKBOARD_SHARED,
                device.operatorName(),
                device.deviceId(),
                device.batteryLevel(),
                null,
                null,
                0,
                device.lat(),
                device.lng(),
                1,
                distanceMeters,
                null,
                null,
                0.0,
                0.0,
                AvailabilitySource.LIVE
        );
    }

    private static MobilityInfo personalMobility(double lat, double lng, MobilityType type) {
        return new MobilityInfo(
                type,
                type.displayName(),
                null,
                100,
                null,
                null,
                0,
                lat,
                lng,
                1,
                0,
                null,
                null,
                0.0,
                0.0,
                AvailabilitySource.OWNED
        );
    }

    /** TAGO API 미제공으로 실 데이터 없음을 출처(ESTIMATED)로 명시. */
    private static MobilityInfo syntheticKickboard(double lat, double lng) {
        return new MobilityInfo(
                MobilityType.KICKBOARD_SHARED,
                SYNTHETIC_KICKBOARD_OPERATOR,
                null,
                SYNTHETIC_KICKBOARD_BATTERY,
                null,
                null,
                0,
                lat,
                lng,
                1,
                0,
                null,
                null,
                0.0,
                0.0,
                AvailabilitySource.ESTIMATED
        );
    }

    private static int distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        return (int) GeoDistance.meters(lat1, lng1, lat2, lng2);
    }

    private static double roundCoordinate(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private record AvailabilityKey(double latBucket, double lngBucket, MobilityType type, boolean dropoff) {
        private static AvailabilityKey of(double lat, double lng, MobilityType type, boolean dropoff) {
            return new AvailabilityKey(roundCoordinate(lat), roundCoordinate(lng), type, dropoff);
        }
    }
}
