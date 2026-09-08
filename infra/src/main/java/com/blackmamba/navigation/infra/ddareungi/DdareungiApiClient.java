package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.domain.route.AvailabilitySource;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStationResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
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

/**
 * 서울시 따릉이 정류소 API 클라이언트.
 *
 * <p>전체 정류소 snapshot 을 TTL 캐시하고 반경 필터는 메모리에서 수행한다.
 * 결과에는 출처({@link AvailabilitySource}) 가 함께 실린다 — 예전엔 클라이언트 전역
 * 필드 하나에 마지막 갱신 결과를 기록해 두었는데, 동시 요청 A 가 신선한 snapshot 을
 * 받았어도 직전 B 의 갱신 실패가 그 필드를 STALE 로 덮어 A 의 응답에 "재사용 결과"
 * 경고가 붙는 오표기가 났다. 출처는 응답 값의 속성이지 클라이언트의 상태가 아니다.
 */
@Component
public class DdareungiApiClient {

    private static final Logger log = LoggerFactory.getLogger(DdareungiApiClient.class);
    private static final String BASE_URL = "http://openapi.seoul.go.kr:8088";
    private final WebClient webClient;
    private final String apiKey;
    private final DdareungiStationFilter filter;
    private final AtomicReference<SnapshotCache> stationSnapshot = new AtomicReference<>();
    private final AtomicReference<Mono<StationSnapshot>> refreshInFlight = new AtomicReference<>();
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
    // T-3: Resilience4j
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public DdareungiApiClient(
            WebClient.Builder webClientBuilder,
            @Value("${ddareungi.api-key}") String apiKey,
            @Value("${navigation.cache.ddareungi-snapshot-ttl-ms:30000}") long snapshotCacheTtlMs,
            @Value("${ddareungi.request-timeout-ms:5000}") long requestTimeoutMs,
            @Value("${ddareungi.refresh-backoff-ms:30000}") long refreshBackoffMs,
            MeterRegistry meterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry
    ) {
        this.apiKey = apiKey;
        this.snapshotCacheTtlMs = snapshotCacheTtlMs;
        this.requestTimeoutMs = requestTimeoutMs;
        this.refreshBackoffMs = refreshBackoffMs;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("ddareungi");
        this.retry = retryRegistry.retry("ddareungi");
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

    /** 정류소 목록 + 그 목록의 출처(실시간/stale/빈 결과). */
    public record StationSnapshot(List<DdareungiStation> stations, AvailabilitySource source) {

        static StationSnapshot live(List<DdareungiStation> stations) {
            return new StationSnapshot(stations, AvailabilitySource.LIVE);
        }

        static StationSnapshot empty() {
            return new StationSnapshot(List.of(), AvailabilitySource.EMPTY);
        }

        StationSnapshot asStale() {
            return new StationSnapshot(stations, AvailabilitySource.STALE);
        }

        StationSnapshot withStations(List<DdareungiStation> filtered) {
            return new StationSnapshot(filtered, source);
        }
    }

    @Observed(name = "external.api.ddareungi.nearby",
            contextualName = "따릉이 반경 내 정류소 조회",
            lowCardinalityKeyValues = {"external.api", "ddareungi"})
    public Mono<StationSnapshot> getNearbyStations(double lat, double lng, int radiusMeters) {
        return getNearbyStations(lat, lng, radiusMeters, true);
    }

    public Mono<StationSnapshot> getNearbyStations(double lat, double lng, int radiusMeters,
                                                   boolean requireAvailableBike) {
        return getStationSnapshot()
                .map(snapshot -> {
                    List<DdareungiStation> nearby = filter.filterNearby(snapshot.stations(), lat, lng, radiusMeters, requireAvailableBike);
                    log.info("[따릉이 API] 캐시된 전체 정류소 {}개 → 반경 {}m 내 {} 정류소 {}개 (출처 {})",
                            snapshot.stations().size(), radiusMeters, requireAvailableBike ? "대여가능" : "반납가능",
                            nearby.size(), snapshot.source());
                    return snapshot.withStations(nearby);
                });
    }

    private Mono<StationSnapshot> getStationSnapshot() {
        long now = System.currentTimeMillis();
        SnapshotCache cached = stationSnapshot.get();
        if (cached != null && !cached.isExpired(now)) {
            snapshotCacheHitCounter.increment();
            return cached.snapshotMono();
        }
        if (refreshBlockedUntilMs.get() > now) {
            refreshShortCircuitCounter.increment();
            return staleOrEmptySnapshot(cached, "backoff active");
        }
        Mono<StationSnapshot> inFlight = refreshInFlight.get();
        if (inFlight != null) {
            snapshotCacheHitCounter.increment();
            return inFlight;
        }

        synchronized (stationSnapshot) {
            SnapshotCache rechecked = stationSnapshot.get();
            if (rechecked != null && !rechecked.isExpired(now)) {
                snapshotCacheHitCounter.increment();
                return rechecked.snapshotMono();
            }
            if (refreshBlockedUntilMs.get() > now) {
                refreshShortCircuitCounter.increment();
                return staleOrEmptySnapshot(rechecked, "backoff active");
            }
            Mono<StationSnapshot> recheckedInFlight = refreshInFlight.get();
            if (recheckedInFlight != null) {
                snapshotCacheHitCounter.increment();
                return recheckedInFlight;
            }
            snapshotCacheMissCounter.increment();

            Mono<StationSnapshot> loader = webClient.get()
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
                    .map(StationSnapshot::live)
                    .doOnNext(snapshot -> {
                        log.info("[따릉이 API] 서울시 전체 정류소 {}개 snapshot 갱신", snapshot.stations().size());
                        stationSnapshot.set(new SnapshotCache(Mono.just(snapshot).cache(), System.currentTimeMillis() + snapshotCacheTtlMs));
                    })
                    // T-3: Retry(안쪽) + CircuitBreaker(바깥) → 최종 onErrorResume 에서 stale snapshot fallback
                    .transformDeferred(RetryOperator.of(retry))
                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
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

    private Mono<StationSnapshot> staleOrEmptySnapshot(SnapshotCache cached, String reason) {
        if (cached != null) {
            staleFallbackCounter.increment();
            log.warn("[따릉이 API] stale snapshot 재사용 (reason={})", reason);
            return cached.snapshotMono().map(StationSnapshot::asStale);
        }
        emptyFallbackCounter.increment();
        log.warn("[따릉이 API] 사용 가능한 snapshot 없음 -> 빈 결과 반환 (reason={})", reason);
        return Mono.just(StationSnapshot.empty());
    }

    private record SnapshotCache(Mono<StationSnapshot> snapshotMono, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
