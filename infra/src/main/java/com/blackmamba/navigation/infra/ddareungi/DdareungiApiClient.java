package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStationResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DdareungiApiClient {

    private static final Logger log = LoggerFactory.getLogger(DdareungiApiClient.class);
    private static final String BASE_URL = "http://openapi.seoul.go.kr:8088";
    private final WebClient webClient;
    private final String apiKey;
    private final DdareungiStationFilter filter;
    private final AtomicReference<SnapshotCache> stationSnapshot = new AtomicReference<>();
    private final Counter snapshotCacheHitCounter;
    private final Counter snapshotCacheMissCounter;
    private final long snapshotCacheTtlMs;

    public DdareungiApiClient(
            WebClient.Builder webClientBuilder,
            @Value("${ddareungi.api-key}") String apiKey,
            @Value("${navigation.cache.ddareungi-snapshot-ttl-ms:30000}") long snapshotCacheTtlMs,
            MeterRegistry meterRegistry
    ) {
        this.apiKey = apiKey;
        this.snapshotCacheTtlMs = snapshotCacheTtlMs;
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
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

        synchronized (stationSnapshot) {
            SnapshotCache rechecked = stationSnapshot.get();
            if (rechecked != null && !rechecked.isExpired(now)) {
                snapshotCacheHitCounter.increment();
                return rechecked.stationsMono();
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
                    .map(DdareungiStationResponse::toStations)
                    .doOnNext(stations -> log.info("[따릉이 API] 서울시 전체 정류소 {}개 snapshot 갱신", stations.size()))
                    .cache();

            stationSnapshot.set(new SnapshotCache(loader, now + snapshotCacheTtlMs));
            return loader;
        }
    }

    private record SnapshotCache(Mono<List<DdareungiStation>> stationsMono, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
