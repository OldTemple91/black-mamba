package com.blackmamba.navigation.infra.adapter;

import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.route.mobility.AvailabilitySource;
import com.blackmamba.navigation.domain.route.mobility.MobilityInfo;
import com.blackmamba.navigation.domain.route.mobility.MobilitySearchHint;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import com.blackmamba.navigation.infra.common.cache.TtlCache;
import com.blackmamba.navigation.infra.common.cache.TtlCacheRegistry;
import com.blackmamba.navigation.infra.ddareungi.DdareungiApiClient;
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
    private final Counter ddareungiFallbackErrorCounter;
    private final Counter kickboardFallbackErrorCounter;
    private final Counter kickboardFallbackEmptyCounter;
    private final TtlCache<AvailabilityKey, Optional<MobilityInfo>> availabilityCache;
    private final TtlCache<SegmentAvailabilityKey, MobilityInfo> segmentAvailabilityCache;
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
        // (start × end × type) 조합 키라 경우의 수가 특히 많다 — registry 의 주기 청소가 힙 잠식을 막는다
        this.segmentAvailabilityCache = cacheRegistry.create("mobility_segment", availabilityCacheTtlMs);
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
        return cachedLookup(lat, lng, type, false, () -> switch (type) {
            case DDAREUNGI -> findNearbyDdareungi(lat, lng);
            case KICKBOARD_SHARED -> findNearbyKickboard(lat, lng);
            case PERSONAL_EBIKE -> Mono.just(Optional.of(personalMobility(lat, lng, MobilityType.PERSONAL_EBIKE, "개인 전기자전거")));
            case PERSONAL_KICKBOARD -> Mono.just(Optional.of(personalMobility(lat, lng, MobilityType.PERSONAL_KICKBOARD, "개인 킥보드")));
        });
    }

    @Override
    public Mono<Optional<MobilityInfo>> findNearbyDropoff(double lat, double lng, MobilityType type) {
        return cachedLookup(lat, lng, type, true, () -> switch (type) {
            case DDAREUNGI -> ddareungiClient.getNearbyStations(lat, lng, searchRadiusMeters, false)
                    .map(snapshot -> snapshot.stations().stream().findFirst()
                            .map(station -> ddareungiInfo(station, distanceMeters(lat, lng, station.lat(), station.lng()), snapshot.source())))
                    .onErrorResume(ex -> {
                        log.error("[따릉이] 반납 정류소 조회 오류: {}", ex.getMessage());
                        ddareungiFallbackErrorCounter.increment();
                        return Mono.just(Optional.<MobilityInfo>empty());
                    });
            case KICKBOARD_SHARED, PERSONAL_EBIKE, PERSONAL_KICKBOARD -> findNearbyMobility(lat, lng, type);
        });
    }

    @Override
    public Mono<MobilityInfo> findSegmentMobility(double startLat, double startLng, double endLat, double endLng, MobilityType type) {
        SegmentAvailabilityKey key = SegmentAvailabilityKey.of(startLat, startLng, endLat, endLng, type);
        return segmentAvailabilityCache.get(key, () -> loadSegmentMobility(startLat, startLng, endLat, endLng, type));
    }

    private Mono<MobilityInfo> loadSegmentMobility(double startLat, double startLng, double endLat, double endLng, MobilityType type) {
        Mono<Optional<MobilityInfo>> pickup = findNearbyMobility(startLat, startLng, type);

        if (type != MobilityType.DDAREUNGI) {
            return pickup.flatMap(Mono::justOrEmpty);
        }

        // pickup/dropoff 는 어느 쪽이 비었는지 진단(NO_PICKUP/NO_DROPOFF)에 쓰이므로 Optional 유지.
        // 세그먼트 결과는 "둘 다 성립" 일 때만 의미가 있어 여기서 empty Mono 로 평탄화한다.
        Mono<Optional<MobilityInfo>> dropoff = findNearbyDropoff(endLat, endLng, type);
        return Mono.zip(pickup, dropoff)
                .flatMap(tuple -> Mono.justOrEmpty(tuple.getT1()
                        .flatMap(pickupInfo -> tuple.getT2()
                                .map(dropoffInfo -> pickupInfo.withDropoffStation(
                                        dropoffInfo.stationId(),
                                        dropoffInfo.stationName(),
                                        dropoffInfo.lat(),
                                        dropoffInfo.lng()
                                ))
                                .filter(info -> !info.hasSamePickupAndDropoffStation()))));
    }

    @Override
    public Mono<Optional<MobilitySearchHint>> findNearestMobilityHint(double lat, double lng, MobilityType type, boolean dropoff) {
        if (type != MobilityType.DDAREUNGI) {
            return Mono.just(Optional.empty());
        }

        int hintRadiusMeters = Math.max(searchRadiusMeters * 2, MIN_HINT_RADIUS_METERS);
        return ddareungiClient.getNearbyStations(lat, lng, hintRadiusMeters, false)
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
                    log.debug("[따릉이] 최근접 정류소 힌트 조회 실패: {}", ex.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    private Mono<Optional<MobilityInfo>> cachedLookup(double lat, double lng, MobilityType type, boolean dropoff,
                                                      Supplier<Mono<Optional<MobilityInfo>>> loaderSupplier) {
        AvailabilityKey key = AvailabilityKey.of(lat, lng, type, dropoff);
        return availabilityCache.get(key, loaderSupplier);
    }

    private Mono<Optional<MobilityInfo>> findNearbyDdareungi(double lat, double lng) {
        return ddareungiClient.getNearbyStations(lat, lng, searchRadiusMeters)
                .map(snapshot -> {
                    log.info("[따릉이] 검색 lat={}, lng={}, 반경={}m → 반경 내 대여가능 정류소 {}개",
                            lat, lng, searchRadiusMeters, snapshot.stations().size());
                    Optional<MobilityInfo> result = snapshot.stations().stream().findFirst()
                            .map(station -> {
                                int dist = distanceMeters(lat, lng, station.lat(), station.lng());
                                log.info("[따릉이] 선택: {} | 대여가능 {}대 | 거리 {}m",
                                        station.stationName(), station.availableCount(), dist);
                                return ddareungiInfo(station, dist, snapshot.source());
                            });
                    if (result.isEmpty()) {
                        log.info("[따릉이] 반경 내 대여 가능한 정류소 없음");
                    }
                    return result;
                })
                .onErrorResume(ex -> {
                    log.error("[따릉이] API 오류: {}", ex.getMessage());
                    ddareungiFallbackErrorCounter.increment();
                    return Mono.just(Optional.<MobilityInfo>empty());
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
                    log.error("[킥보드] API 오류: {} → 가상 킥보드(추정)로 폴백", ex.getMessage());
                    kickboardFallbackErrorCounter.increment();
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

    private static MobilityInfo personalMobility(double lat, double lng, MobilityType type, String operatorName) {
        return new MobilityInfo(
                type,
                operatorName,
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

    private record SegmentAvailabilityKey(double startLatBucket,
                                          double startLngBucket,
                                          double endLatBucket,
                                          double endLngBucket,
                                          MobilityType type) {
        private static SegmentAvailabilityKey of(double startLat, double startLng, double endLat, double endLng, MobilityType type) {
            return new SegmentAvailabilityKey(
                    roundCoordinate(startLat),
                    roundCoordinate(startLng),
                    roundCoordinate(endLat),
                    roundCoordinate(endLng),
                    type
            );
        }
    }
}
