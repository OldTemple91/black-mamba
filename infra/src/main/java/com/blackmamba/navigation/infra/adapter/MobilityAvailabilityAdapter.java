package com.blackmamba.navigation.infra.adapter;

import com.blackmamba.navigation.domain.location.GeoDistance;

import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.domain.route.MobilityInfo;
import com.blackmamba.navigation.domain.route.MobilitySearchHint;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.infra.ddareungi.DdareungiApiClient;
import com.blackmamba.navigation.infra.kickboard.KickboardApiClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MobilityAvailabilityAdapter implements MobilityAvailabilityPort {

    private static final Logger log = LoggerFactory.getLogger(MobilityAvailabilityAdapter.class);
    private final DdareungiApiClient ddareungiClient;
    private final KickboardApiClient kickboardClient;
    private final Counter ddareungiFallbackErrorCounter;
    private final Counter kickboardFallbackErrorCounter;
    private final Counter kickboardFallbackEmptyCounter;
    private final Counter availabilityCacheHitCounter;
    private final Counter availabilityCacheMissCounter;
    private final Counter segmentCacheHitCounter;
    private final Counter segmentCacheMissCounter;
    private final ConcurrentHashMap<AvailabilityKey, CacheEntry<Optional<MobilityInfo>>> availabilityCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SegmentAvailabilityKey, CacheEntry<Optional<MobilityInfo>>> segmentAvailabilityCache = new ConcurrentHashMap<>();
    private final long availabilityCacheTtlMs;
    private final int searchRadiusMeters;
    private final boolean tagoEnabled;

    public MobilityAvailabilityAdapter(DdareungiApiClient ddareungiClient,
                                       KickboardApiClient kickboardClient,
                                       @org.springframework.beans.factory.annotation.Value("${navigation.mobility.search-radius-meters:700}") int searchRadiusMeters,
                                       @org.springframework.beans.factory.annotation.Value("${navigation.cache.mobility-availability-ttl-ms:20000}") long availabilityCacheTtlMs,
                                       @org.springframework.beans.factory.annotation.Value("${tago.enabled:false}") boolean tagoEnabled,
                                       MeterRegistry meterRegistry) {
        this.ddareungiClient = ddareungiClient;
        this.kickboardClient = kickboardClient;
        this.searchRadiusMeters = searchRadiusMeters;
        this.availabilityCacheTtlMs = availabilityCacheTtlMs;
        this.tagoEnabled = tagoEnabled;
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
        this.availabilityCacheHitCounter = meterRegistry.counter(
                "navigation.cache.total",
                "cache", "mobility_availability",
                "result", "hit"
        );
        this.availabilityCacheMissCounter = meterRegistry.counter(
                "navigation.cache.total",
                "cache", "mobility_availability",
                "result", "miss"
        );
        this.segmentCacheHitCounter = meterRegistry.counter(
                "navigation.cache.total",
                "cache", "mobility_segment",
                "result", "hit"
        );
        this.segmentCacheMissCounter = meterRegistry.counter(
                "navigation.cache.total",
                "cache", "mobility_segment",
                "result", "miss"
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
                    .map(stations -> stations.stream().findFirst()
                            .map(s -> new MobilityInfo(
                                    MobilityType.DDAREUNGI,
                                    "서울시 따릉이",
                                    null,
                                    100,
                                    s.stationId(),
                                    s.stationName(),
                                    s.rackTotalCount(),
                                    s.lat(),
                                    s.lng(),
                                    s.availableCount(),
                                    distanceMeters(lat, lng, s.lat(), s.lng()),
                                    null,
                                    null,
                                    0.0,
                                    0.0,
                                    ddareungiClient.currentSnapshotMode()
                            )))
                    .onErrorResume(ex -> {
                        log.error("[따릉이] 반납 정류소 조회 오류: {}", ex.getMessage());
                        ddareungiFallbackErrorCounter.increment();
                        return Mono.just(Optional.<MobilityInfo>empty());
                    });
            case KICKBOARD_SHARED, PERSONAL_EBIKE, PERSONAL_KICKBOARD -> findNearbyMobility(lat, lng, type);
        });
    }

    @Override
    public Mono<Optional<MobilityInfo>> findSegmentMobility(double startLat, double startLng, double endLat, double endLng, MobilityType type) {
        long now = System.currentTimeMillis();
        SegmentAvailabilityKey key = SegmentAvailabilityKey.of(startLat, startLng, endLat, endLng, type);
        return segmentAvailabilityCache.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isExpired(now)) {
                segmentCacheHitCounter.increment();
                return existing;
            }
            segmentCacheMissCounter.increment();
            return new CacheEntry<>(loadSegmentMobility(startLat, startLng, endLat, endLng, type).cache(), now + availabilityCacheTtlMs);
        }).value();
    }

    private Mono<Optional<MobilityInfo>> loadSegmentMobility(double startLat, double startLng, double endLat, double endLng, MobilityType type) {
        Mono<Optional<MobilityInfo>> pickup = findNearbyMobility(startLat, startLng, type);

        if (type != MobilityType.DDAREUNGI) {
            return pickup;
        }

        Mono<Optional<MobilityInfo>> dropoff = findNearbyDropoff(endLat, endLng, type);
        return Mono.zip(pickup, dropoff)
                .map(tuple -> tuple.getT1()
                        .flatMap(pickupInfo -> tuple.getT2()
                                .map(dropoffInfo -> pickupInfo.withDropoffStation(
                                        dropoffInfo.stationId(),
                                        dropoffInfo.stationName(),
                                        dropoffInfo.lat(),
                                        dropoffInfo.lng()
                                ))
                                .filter(info -> !info.hasSamePickupAndDropoffStation())));
    }

    @Override
    public Mono<Optional<MobilitySearchHint>> findNearestMobilityHint(double lat, double lng, MobilityType type, boolean dropoff) {
        if (type != MobilityType.DDAREUNGI) {
            return Mono.just(Optional.empty());
        }

        int hintRadiusMeters = Math.max(searchRadiusMeters * 2, 1400);
        return ddareungiClient.getNearbyStations(lat, lng, hintRadiusMeters, false)
                .map(stations -> stations.stream().findFirst()
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
                                                      java.util.function.Supplier<Mono<Optional<MobilityInfo>>> loaderSupplier) {
        long now = System.currentTimeMillis();
        AvailabilityKey key = AvailabilityKey.of(lat, lng, type, dropoff);
        return availabilityCache.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isExpired(now)) {
                availabilityCacheHitCounter.increment();
                return existing;
            }
            availabilityCacheMissCounter.increment();
            return new CacheEntry<>(loaderSupplier.get().cache(), now + availabilityCacheTtlMs);
        }).value();
    }

    private Mono<Optional<MobilityInfo>> findNearbyDdareungi(double lat, double lng) {
        return ddareungiClient.getNearbyStations(lat, lng, searchRadiusMeters)
                .map(stations -> {
                    log.info("[따릉이] 검색 lat={}, lng={}, 반경={}m → 반경 내 대여가능 정류소 {}개",
                            lat, lng, searchRadiusMeters, stations.size());
                    Optional<MobilityInfo> result = stations.stream().findFirst()
                            .map(s -> {
                                int dist = distanceMeters(lat, lng, s.lat(), s.lng());
                                log.info("[따릉이] 선택: {} | 대여가능 {}대 | 거리 {}m",
                                        s.stationName(), s.availableCount(), dist);
                                return new MobilityInfo(
                                        MobilityType.DDAREUNGI,
                                        "서울시 따릉이",
                                        null,
                                        100,
                                        s.stationId(),
                                        s.stationName(),
                                        s.rackTotalCount(),
                                        s.lat(),
                                        s.lng(),
                                        s.availableCount(),
                                        dist,
                                        null,
                                        null,
                                        0.0,
                                        0.0,
                                        ddareungiClient.currentSnapshotMode()
                                );
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
                            .map(d -> {
                                int dist = distanceMeters(lat, lng, d.lat(), d.lng());
                                log.info("[킥보드] 선택: {} | 기기 ID {} | 배터리 {}% | 거리 {}m",
                                        d.operatorName(), d.deviceId(), d.batteryLevel(), dist);
                                return new MobilityInfo(
                                        MobilityType.KICKBOARD_SHARED,
                                        d.operatorName(),
                                        d.deviceId(),
                                        d.batteryLevel(),
                                        null,
                                        null,
                                        0,
                                        d.lat(),
                                        d.lng(),
                                        1,
                                        dist,
                                        null,
                                        null,
                                        0.0,
                                        0.0,
                                        "LIVE"
                                );
                            });
                })
                .onErrorResume(ex -> {
                    log.error("[킥보드] API 오류: {} → 가상 킥보드(추정)로 폴백", ex.getMessage());
                    kickboardFallbackErrorCounter.increment();
                    return Mono.just(Optional.of(syntheticKickboard(lat, lng)));
                });
    }

    private MobilityInfo personalMobility(double lat, double lng, MobilityType type, String operatorName) {
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
            "OWNED"
        );
    }

    // B-4: battery=0 노출 방지. TAGO API 미제공으로 실 데이터 없음을 명시.
    private MobilityInfo syntheticKickboard(double lat, double lng) {
        return new MobilityInfo(
                MobilityType.KICKBOARD_SHARED,
                "공유 킥보드(추정)",
                null,
                50,   // 실 배터리 미확인 → 50% 기본값 표시
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
            "ESTIMATED"
        );
    }

    private int distanceMeters(double lat1, double lng1, double lat2, double lng2) {
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


    /**
     * 만료 캐시 엔트리 주기 청소.
     * segmentAvailabilityCache 는 (start × end × type) 조합 키라 경우의 수가
     * 특히 많아 evict 없이는 장기 운영 시 힙 잠식이 가장 빠른 캐시다.
     */
    @Scheduled(fixedDelayString = "${navigation.cache.purge-interval-ms:300000}")
    void purgeExpiredCacheEntries() {
        long now = System.currentTimeMillis();
        availabilityCache.entrySet().removeIf(e -> e.getValue().isExpired(now));
        segmentAvailabilityCache.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    private record CacheEntry<T>(Mono<T> value, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
