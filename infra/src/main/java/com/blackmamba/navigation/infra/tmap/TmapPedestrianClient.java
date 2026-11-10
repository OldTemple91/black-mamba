package com.blackmamba.navigation.infra.tmap;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.infra.common.GeohashKeyGenerator;
import com.blackmamba.navigation.infra.common.cache.TtlCache;
import com.blackmamba.navigation.infra.common.cache.TtlCacheRegistry;
import com.blackmamba.navigation.infra.common.http.ExternalApiClientException;
import com.blackmamba.navigation.infra.common.http.ExternalApiException;
import com.blackmamba.navigation.infra.common.http.WebClients;
import com.blackmamba.navigation.infra.tmap.dto.TmapPedestrianResponse;
import com.blackmamba.navigation.infra.common.resilience.ExternalApiResilience;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TMAP 보행자 경로 API 클라이언트.
 * 실제 도로 거리(m)와 경로 좌표(GeoJSON LineString)를 함께 반환.
 * 실패 시 Optional.empty() 반환 → MobilityTimeAdapter에서 haversine fallback.
 * <p>
 * 실패 폴백은 캐시하지 않는다 — 한 번의 오류가 TTL(5분) 동안 그 구간을 직선 추정으로 고정하지 않게.
 * HTTP 오류는 {@link ExternalApiException} 으로 바꿔 회로 차단기가 실패로 집계한다(401·502 포함).
 */
@Component
public class TmapPedestrianClient {

    private static final Logger log = LoggerFactory.getLogger(TmapPedestrianClient.class);
    private final WebClient webClient;
    private final String appKey;
    private final Counter fallbackCounter;
    private final Counter quotaFallbackCounter;
    private final Counter quotaShortCircuitCounter;
    // B-3: Geohash 기반 키 (150m 격자 내 좌표는 동일 키로 공유)
    private final TtlCache<String, Optional<TmapRouteData>> routeCache;
    private final long quotaBackoffMs;
    private final AtomicLong quotaBlockedUntilMs = new AtomicLong(0L);
    /** backoff 창마다 한 번만 WARN — 요청당 구간 수만큼 반복되면 실제 경고가 묻힌다. 집계는 quotaShortCircuitCounter 가 한다 */
    private final AtomicBoolean quotaBackoffLogged = new AtomicBoolean(false);
    private final ExternalApiResilience.Guard guard;

    public TmapPedestrianClient(
            WebClient.Builder webClientBuilder,
            @Value("${tmap.app-key}") String appKey,
            @Value("${tmap.base-url:https://apis.openapi.sk.com}") String baseUrl,
            @Value("${navigation.cache.tmap-pedestrian-route-ttl-ms:300000}") long routeCacheTtlMs,
            @Value("${navigation.cache.tmap-rate-limit-backoff-ms:3600000}") long quotaBackoffMs,
            @Value("${tmap.request-timeout-ms:5000}") long requestTimeoutMs,
            MeterRegistry meterRegistry,
            ExternalApiResilience resilience,
            TtlCacheRegistry cacheRegistry
    ) {
        this.webClient = WebClients.withTimeout(webClientBuilder, baseUrl, requestTimeoutMs);
        this.appKey = appKey;
        this.routeCache = cacheRegistry.create("tmap_pedestrian_route", routeCacheTtlMs);
        this.quotaBackoffMs = quotaBackoffMs;
        this.guard = resilience.forApi("tmap");
        this.fallbackCounter = meterRegistry.counter(
                "navigation.tmap.fallback.total", "reason", "error");
        this.quotaFallbackCounter = meterRegistry.counter(
                "navigation.tmap.fallback.total", "reason", "quota_exceeded");
        this.quotaShortCircuitCounter = meterRegistry.counter(
                "navigation.tmap.fallback.total", "reason", "quota_short_circuit");
    }

    /**
     * 두 지점 간 보행자 경로 조회.
     * 성공 시 (도로 거리m, 실제 경로 좌표)를 담은 Optional 반환.
     * 실패 또는 경로 없으면 Optional.empty().
     */
    @Observed(name = "external.api.tmap.route",
            contextualName = "Tmap 보행자 경로 조회",
            lowCardinalityKeyValues = {"external.api", "tmap"})
    public Mono<Optional<TmapRouteData>> getRoute(Location origin, Location destination) {
        long blockedUntil = quotaBlockedUntilMs.get();
        long now = System.currentTimeMillis();
        if (blockedUntil > now) {
            quotaShortCircuitCounter.increment();
            if (quotaBackoffLogged.compareAndSet(false, true)) {
                log.warn("[TMAP] quota backoff active -> API 호출 생략 ({}초 남음, 이 창에서 1회만 기록)", (blockedUntil - now) / 1000);
            }
            return Mono.just(Optional.empty());
        }
        String key = GeohashKeyGenerator.forRoute(origin, destination);
        return routeCache.get(key, () -> requestRoute(origin, destination), Optional::isPresent);
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
                .onStatus(HttpStatusCode::isError, response -> ExternalApiException.from("TMAP", response))
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
                // T-3: Retry(안쪽) + CircuitBreaker(바깥). 최종 onErrorResume 으로 해버사인 fallback.
                .as(guard::protect)
                .onErrorResume(ex -> {
                    if (isTooManyRequests(ex)) {
                        quotaFallbackCounter.increment();
                        long blockedUntil = System.currentTimeMillis() + quotaBackoffMs;
                        quotaBlockedUntilMs.set(blockedUntil);
                        quotaBackoffLogged.set(false);
                        log.warn("[TMAP] 429 감지 -> {}ms 동안 외부 호출 차단 후 haversine fallback", quotaBackoffMs);
                        return Mono.just(Optional.empty());
                    }
                    fallbackCounter.increment();
                    log.warn("[TMAP] 보행자 경로 조회 실패(최종) → haversine fallback. 원인: {}", ex.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    private boolean isTooManyRequests(Throwable throwable) {
        return throwable instanceof ExternalApiClientException client && client.status() == HttpStatus.TOO_MANY_REQUESTS.value();
    }

    public record TmapRouteData(int distanceMeters, List<Location> coordinates) {}
}
