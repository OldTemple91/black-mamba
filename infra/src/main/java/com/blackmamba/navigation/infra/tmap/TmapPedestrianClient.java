package com.blackmamba.navigation.infra.tmap;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.infra.tmap.dto.TmapPedestrianResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TMAP 보행자 경로 API 클라이언트.
 * 실제 도로 거리(m)와 경로 좌표(GeoJSON LineString)를 함께 반환.
 * 실패 시 Optional.empty() 반환 → MobilityTimeAdapter에서 haversine fallback.
 */
@Component
public class TmapPedestrianClient {

    private static final String BASE_URL = "https://apis.openapi.sk.com";
    private static final Logger log = LoggerFactory.getLogger(TmapPedestrianClient.class);
    private final WebClient webClient;
    private final String appKey;
    private final Counter fallbackCounter;
    private final Counter routeCacheHitCounter;
    private final Counter routeCacheMissCounter;
    private final ConcurrentHashMap<RouteKey, CacheEntry<Optional<TmapRouteData>>> routeCache = new ConcurrentHashMap<>();
    private final long routeCacheTtlMs;

    public TmapPedestrianClient(
            WebClient.Builder webClientBuilder,
            @Value("${tmap.app-key}") String appKey,
            @Value("${navigation.cache.tmap-pedestrian-route-ttl-ms:300000}") long routeCacheTtlMs,
            MeterRegistry meterRegistry
    ) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.appKey = appKey;
        this.routeCacheTtlMs = routeCacheTtlMs;
        this.fallbackCounter = meterRegistry.counter(
                "navigation.tmap.fallback.total", "reason", "error");
        this.routeCacheHitCounter = meterRegistry.counter(
                "navigation.cache.total",
                "cache", "tmap_pedestrian_route",
                "result", "hit"
        );
        this.routeCacheMissCounter = meterRegistry.counter(
                "navigation.cache.total",
                "cache", "tmap_pedestrian_route",
                "result", "miss"
        );
    }

    /**
     * 두 지점 간 보행자 경로 조회.
     * 성공 시 (도로 거리m, 실제 경로 좌표)를 담은 Optional 반환.
     * 실패 또는 경로 없으면 Optional.empty().
     */
    public Mono<Optional<TmapRouteData>> getRoute(Location origin, Location destination) {
        RouteKey key = new RouteKey(origin, destination);
        long now = System.currentTimeMillis();
        return routeCache.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isExpired(now)) {
                routeCacheHitCounter.increment();
                return existing;
            }
            routeCacheMissCounter.increment();
            return new CacheEntry<>(requestRoute(origin, destination).cache(), now + routeCacheTtlMs);
        }).value();
    }

    private Mono<Optional<TmapRouteData>> requestRoute(Location origin, Location destination) {
        Map<String, Object> body = Map.of(
                "startX", String.valueOf(origin.lng()),
                "startY", String.valueOf(origin.lat()),
                "endX",   String.valueOf(destination.lng()),
                "endY",   String.valueOf(destination.lat()),
                "startName", "출발지",
                "endName",   "도착지"
        );

        return webClient.post()
                .uri("/tmap/routes/pedestrian?version=1")
                .header("appKey", appKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(TmapPedestrianResponse.class)
                .map(response -> {
                    int dist = response.totalDistanceMeters();
                    if (dist > 0) {
                        List<Location> coords = response.routeCoordinates();
                        log.debug("[TMAP] 도로 거리 {}m, 좌표 {}개 ({} → {})",
                                dist, coords.size(), origin.name(), destination.name());
                        return Optional.of(new TmapRouteData(dist, coords));
                    }
                    log.warn("[TMAP] 거리 0 반환 — 경로 없음 ({} → {})",
                            origin.name(), destination.name());
                    return Optional.<TmapRouteData>empty();
                })
                .onErrorResume(ex -> {
                    fallbackCounter.increment();
                    log.warn("[TMAP] 보행자 경로 조회 실패 → haversine fallback. 원인: {}", ex.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    public record TmapRouteData(int distanceMeters, List<Location> coordinates) {}

    private record RouteKey(double originLat, double originLng, double destinationLat, double destinationLng) {
        private RouteKey(Location origin, Location destination) {
            this(origin.lat(), origin.lng(), destination.lat(), destination.lng());
        }
    }

    private record CacheEntry<T>(Mono<T> value, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
