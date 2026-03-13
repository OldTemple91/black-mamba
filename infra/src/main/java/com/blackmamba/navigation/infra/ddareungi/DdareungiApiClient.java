package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStationResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DdareungiApiClient {

    private static final Logger log = LoggerFactory.getLogger(DdareungiApiClient.class);
    private static final String BASE_URL = "http://openapi.seoul.go.kr:8088";
    private final WebClient webClient;
    private final String apiKey;
    private final DdareungiStationFilter filter;
    private final AtomicReference<SnapshotCache> stationSnapshot = new AtomicReference<>();
    private final AtomicReference<Mono<List<DdareungiStation>>> refreshInFlight = new AtomicReference<>();
    private final AtomicLong refreshBlockedUntilMs = new AtomicLong(0L);
    private final Counter snapshotCacheHitCounter;
    private final Counter snapshotCacheMissCounter;
    private final Counter staleFallbackCounter;
    private final Counter emptyFallbackCounter;
    private final Counter refreshBackoffCounter;
    private final Counter refreshShortCircuitCounter;
    private final long snapshotCacheTtlMs;
    private final long requestTimeoutMs;
    private final long refreshBackoffMs;

    public DdareungiApiClient(
            WebClient.Builder webClientBuilder,
            @Value("${ddareungi.api-key}") String apiKey,
            @Value("${navigation.cache.ddareungi-snapshot-ttl-ms:30000}") long snapshotCacheTtlMs,
            @Value("${navigation.ddareungi.request-timeout-ms:5000}") long requestTimeoutMs,
            @Value("${navigation.ddareungi.refresh-backoff-ms:30000}") long refreshBackoffMs,
            MeterRegistry meterRegistry
    ) {
        this.apiKey = apiKey;
        this.snapshotCacheTtlMs = snapshotCacheTtlMs;
        this.requestTimeoutMs = requestTimeoutMs;
        this.refreshBackoffMs = refreshBackoffMs;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) requestTimeoutMs)
                .responseTimeout(Duration.ofMillis(requestTimeoutMs));
        this.webClient = webClientBuilder
                .baseUrl(BASE_URL)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        this.filter = new DdareungiStationFilter();
        this.snapshotCacheHitCounter = meterRegistry.counter(
                "navigation.cache.total",
                "cache", "ddareungi_snapshot",
                "result", "hit"
        );
        this.snapshotCacheMissCounter = meterRegistry.counter(
                "navigation.cache.total",
                "cache", "ddareungi_snapshot",
                "result", "miss"
        );
        this.staleFallbackCounter = meterRegistry.counter(
                "navigation.ddareungi.fallback.total",
                "reason", "stale_snapshot"
        );
        this.emptyFallbackCounter = meterRegistry.counter(
                "navigation.ddareungi.fallback.total",
                "reason", "empty_snapshot"
        );
        this.refreshBackoffCounter = meterRegistry.counter(
                "navigation.ddareungi.fallback.total",
                "reason", "refresh_backoff"
        );
        this.refreshShortCircuitCounter = meterRegistry.counter(
                "navigation.ddareungi.fallback.total",
                "reason", "refresh_short_circuit"
        );
    }

    public Mono<List<DdareungiStation>> getNearbyStations(double lat, double lng, int radiusMeters) {
        return getNearbyStations(lat, lng, radiusMeters, true);
    }

    public Mono<List<DdareungiStation>> getNearbyStations(double lat, double lng, int radiusMeters,
                                                          boolean requireAvailableBike) {
        return getStationSnapshot()
                .map(all -> {
                    List<DdareungiStation> nearby = filter.filterNearby(all, lat, lng, radiusMeters, requireAvailableBike);
                    log.info("[따릉이 API] 캐시된 전체 정류소 {}개 → 반경 {}m 내 {} 정류소 {}개",
                            all.size(), radiusMeters, requireAvailableBike ? "대여가능" : "반납가능", nearby.size());
                    return nearby;
                });
    }

    private Mono<List<DdareungiStation>> getStationSnapshot() {
        long now = System.currentTimeMillis();
        SnapshotCache cached = stationSnapshot.get();
        if (cached != null && !cached.isExpired(now)) {
            snapshotCacheHitCounter.increment();
            return cached.stationsMono();
        }
        if (refreshBlockedUntilMs.get() > now) {
            refreshShortCircuitCounter.increment();
            return staleOrEmptySnapshot(cached, "backoff active");
        }
        Mono<List<DdareungiStation>> inFlight = refreshInFlight.get();
        if (inFlight != null) {
            snapshotCacheHitCounter.increment();
            return inFlight;
        }

        synchronized (stationSnapshot) {
            SnapshotCache rechecked = stationSnapshot.get();
            if (rechecked != null && !rechecked.isExpired(now)) {
                snapshotCacheHitCounter.increment();
                return rechecked.stationsMono();
            }
            if (refreshBlockedUntilMs.get() > now) {
                refreshShortCircuitCounter.increment();
                return staleOrEmptySnapshot(rechecked, "backoff active");
            }
            Mono<List<DdareungiStation>> recheckedInFlight = refreshInFlight.get();
            if (recheckedInFlight != null) {
                snapshotCacheHitCounter.increment();
                return recheckedInFlight;
            }
            snapshotCacheMissCounter.increment();

            Mono<List<DdareungiStation>> loader = webClient.get()
                    .uri("/{apiKey}/json/bikeList/1/1000/", apiKey)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            response -> response.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(
                                            new RuntimeException("따릉이 API 오류: " + response.statusCode() + " " + body)
                                    ))
                    )
                    .bodyToMono(DdareungiStationResponse.class)
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .map(DdareungiStationResponse::toStations)
                    .doOnNext(stations -> {
                        log.info("[따릉이 API] 서울시 전체 정류소 {}개 snapshot 갱신", stations.size());
                        stationSnapshot.set(new SnapshotCache(Mono.just(stations).cache(), System.currentTimeMillis() + snapshotCacheTtlMs));
                    })
                    .onErrorResume(ex -> {
                        long blockedUntil = System.currentTimeMillis() + refreshBackoffMs;
                        refreshBlockedUntilMs.set(blockedUntil);
                        refreshBackoffCounter.increment();
                        log.warn("[따릉이 API] snapshot 조회 실패 -> {}ms 동안 refresh backoff. 원인: {}", refreshBackoffMs, ex.getMessage());
                        return staleOrEmptySnapshot(stationSnapshot.get(), ex.getMessage());
                    })
                    .doFinally(signalType -> refreshInFlight.set(null))
                    .cache();

            refreshInFlight.set(loader);
            return loader;
        }
    }

    private Mono<List<DdareungiStation>> staleOrEmptySnapshot(SnapshotCache cached, String reason) {
        if (cached != null) {
            staleFallbackCounter.increment();
            log.warn("[따릉이 API] stale snapshot 재사용 (reason={})", reason);
            return cached.stationsMono();
        }
        emptyFallbackCounter.increment();
        log.warn("[따릉이 API] 사용 가능한 snapshot 없음 -> 빈 결과 반환 (reason={})", reason);
        return Mono.just(List.of());
    }

    private record SnapshotCache(Mono<List<DdareungiStation>> stationsMono, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
