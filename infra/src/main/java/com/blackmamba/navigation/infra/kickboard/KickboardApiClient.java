package com.blackmamba.navigation.infra.kickboard;

import com.blackmamba.navigation.infra.kickboard.dto.KickboardDevice;
import com.blackmamba.navigation.infra.kickboard.dto.KickboardResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.blackmamba.navigation.infra.common.http.ExternalApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TAGO 공유 킥보드 API 클라이언트.
 *
 * <p>snapshot 갱신 규칙은 {@code DdareungiApiClient} 와 동일하다:
 * <ul>
 *   <li>성공한 응답만 snapshot 으로 저장 — 실패한 Mono 를 TTL 동안 재생하지 않는다</li>
 *   <li>갱신 중 동시 요청은 in-flight Mono 를 공유 (upstream 1회 호출)</li>
 *   <li>실패 시 stale snapshot 이 있으면 재사용, 없으면 빈 목록</li>
 * </ul>
 */
@Component
public class KickboardApiClient {

    private static final Logger log = LoggerFactory.getLogger(KickboardApiClient.class);
    private static final String BASE_URL = "http://apis.data.go.kr/1613000/PersonalMobilityInfo";
    private final WebClient webClient;
    private final String apiKey;
    private final String cityCode;
    private final KickboardDeviceFilter filter;
    private final AtomicReference<DeviceSnapshotCache> deviceSnapshot = new AtomicReference<>();
    private final AtomicReference<Mono<List<KickboardDevice>>> refreshInFlight = new AtomicReference<>();
    private final Counter snapshotCacheHitCounter;
    private final Counter snapshotCacheMissCounter;
    private final Counter staleFallbackCounter;
    private final Counter emptyFallbackCounter;
    private final long snapshotCacheTtlMs;
    private final long requestTimeoutMs;
    // T-3: Resilience4j
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public KickboardApiClient(
            WebClient.Builder webClientBuilder,
            @Value("${tago.api-key}") String apiKey,
            @Value("${tago.city-code}") String cityCode,
            @Value("${navigation.cache.kickboard-snapshot-ttl-ms:30000}") long snapshotCacheTtlMs,
            @Value("${tago.request-timeout-ms:5000}") long requestTimeoutMs,
            MeterRegistry meterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry
    ) {
        this.apiKey = apiKey;
        this.cityCode = cityCode;
        this.snapshotCacheTtlMs = snapshotCacheTtlMs;
        this.requestTimeoutMs = requestTimeoutMs;
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.filter = new KickboardDeviceFilter();
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("kickboard");
        this.retry = retryRegistry.retry("kickboard");
        this.snapshotCacheHitCounter = meterRegistry.counter(
                "navigation.cache.total",
                "cache", "kickboard_snapshot",
                "result", "hit"
        );
        this.snapshotCacheMissCounter = meterRegistry.counter(
                "navigation.cache.total",
                "cache", "kickboard_snapshot",
                "result", "miss"
        );
        this.staleFallbackCounter = meterRegistry.counter(
                "navigation.kickboard.fallback.total",
                "reason", "stale_snapshot"
        );
        this.emptyFallbackCounter = meterRegistry.counter(
                "navigation.kickboard.fallback.total",
                "reason", "empty_snapshot"
        );
    }

    /**
     * GetPMListByProvider: 지역별 운영사기반 탑승가능 공유전동킥보드 목록 조회
     * - cityCode: 필수 (서울 코드 → application.yml tago.city-code)
     * - providerName: 생략 시 전체 운영사 조회
     * - _type=json: JSON 응답 강제
     */
    public Mono<List<KickboardDevice>> getNearbyDevices(double lat, double lng, int radiusMeters) {
        return getDeviceSnapshot()
                .map(all -> {
                    List<KickboardDevice> nearby = filter.filterNearby(all, lat, lng, radiusMeters);
                    log.info("[킥보드 API] 캐시된 전체 기기 {}개 → 반경 {}m 내 배터리≥20% {}개",
                            all.size(), radiusMeters, nearby.size());
                    return nearby;
                });
    }

    private Mono<List<KickboardDevice>> getDeviceSnapshot() {
        long now = System.currentTimeMillis();
        DeviceSnapshotCache cached = deviceSnapshot.get();
        if (cached != null && !cached.isExpired(now)) {
            snapshotCacheHitCounter.increment();
            return cached.devicesMono();
        }
        Mono<List<KickboardDevice>> inFlight = refreshInFlight.get();
        if (inFlight != null) {
            snapshotCacheHitCounter.increment();
            return inFlight;
        }

        synchronized (deviceSnapshot) {
            DeviceSnapshotCache rechecked = deviceSnapshot.get();
            if (rechecked != null && !rechecked.isExpired(now)) {
                snapshotCacheHitCounter.increment();
                return rechecked.devicesMono();
            }
            Mono<List<KickboardDevice>> recheckedInFlight = refreshInFlight.get();
            if (recheckedInFlight != null) {
                snapshotCacheHitCounter.increment();
                return recheckedInFlight;
            }
            snapshotCacheMissCounter.increment();

            Mono<List<KickboardDevice>> loader = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/GetPMListByProvider")
                            .queryParam("serviceKey", apiKey)
                            .queryParam("cityCode", cityCode)
                            .queryParam("numOfRows", 1000)
                            .queryParam("_type", "json")
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response -> ExternalApiException.from("TAGO", response))
                    .bodyToMono(KickboardResponse.class)
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .map(KickboardResponse::toDevices)
                    // 성공한 응답만 snapshot 으로 저장 → 실패 Mono 가 TTL 동안 재생되지 않는다
                    .doOnNext(devices -> {
                        log.info("[킥보드 API] 전체 기기 {}개 snapshot 갱신", devices.size());
                        deviceSnapshot.set(new DeviceSnapshotCache(Mono.just(devices).cache(), System.currentTimeMillis() + snapshotCacheTtlMs));
                    })
                    // T-3: Retry(안쪽) + CircuitBreaker(바깥) → 최종 onErrorResume 에서 stale snapshot fallback
                    .transformDeferred(RetryOperator.of(retry))
                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                    .onErrorResume(ex -> {
                        log.warn("[킥보드 API] snapshot 조회 실패. 원인: {}", ex.getMessage());
                        return staleOrEmptySnapshot(deviceSnapshot.get());
                    })
                    .doFinally(signalType -> refreshInFlight.set(null))
                    .cache();

            refreshInFlight.set(loader);
            return loader;
        }
    }

    private Mono<List<KickboardDevice>> staleOrEmptySnapshot(DeviceSnapshotCache cached) {
        if (cached != null) {
            staleFallbackCounter.increment();
            log.warn("[킥보드 API] stale snapshot 재사용");
            return cached.devicesMono();
        }
        emptyFallbackCounter.increment();
        return Mono.just(List.of());
    }

    private record DeviceSnapshotCache(Mono<List<KickboardDevice>> devicesMono, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
