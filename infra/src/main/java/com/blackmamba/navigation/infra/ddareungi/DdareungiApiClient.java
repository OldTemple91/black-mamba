package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.domain.route.mobility.AvailabilitySource;
import com.blackmamba.navigation.infra.common.http.ExternalApiException;
import com.blackmamba.navigation.infra.common.http.WebClients;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStationResponse;
import com.blackmamba.navigation.infra.common.resilience.ExternalApiResilience;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 서울시 따릉이 정류소 API 클라이언트.
 *
 * <p>전체 정류소 snapshot 을 TTL 캐시하고 반경 필터는 메모리에서 수행한다.
 * 결과에는 출처({@link AvailabilitySource}) 가 함께 실린다 — 예전엔 클라이언트 전역
 * 필드 하나에 마지막 갱신 결과를 기록해 두었는데, 동시 요청 A 가 신선한 snapshot 을
 * 받았어도 직전 B 의 갱신 실패가 그 필드를 STALE 로 덮어 A 의 응답에 "재사용 결과"
 * 경고가 붙는 오표기가 났다. 출처는 응답 값의 속성이지 클라이언트의 상태가 아니다.
 *
 * <h3>snapshot 신뢰 규칙</h3>
 * <ul>
 *   <li>API 는 1회 최대 1,000행이라 서울 전체(약 2,700개)를 페이지로 이어 받는다. 응답에 전체 건수가 없어
 *       "페이지가 가득 찼으면 다음 페이지" 로 진행한다</li>
 *   <li>HTTP 200 오류 봉투(JSON)·XML 오류 본문은 예외로 바꿔 재시도·회로·stale 폴백을 태운다</li>
 *   <li>정류소 0개의 "성공" 도 실패로 본다 — 서울 전체가 0개일 수는 없다. 실패와 같은 경로라
 *       이전 snapshot 재사용·나이 상한·backoff 가 그대로 적용된다</li>
 *   <li>stale 재사용은 조회 후 {@code ddareungi.max-stale-age-ms} 까지만 — 몇 시간 전 재고로 "대여 여유" 를 판정하지 않는다</li>
 *   <li>재시도는 페이지 단위 — 3페이지째 실패가 앞 페이지까지 다시 받게 하지 않는다. 부분 결과는 저장하지 않는다</li>
 * </ul>
 */
@Component
public class DdareungiApiClient {

    private static final Logger log = LoggerFactory.getLogger(DdareungiApiClient.class);
    /** 서울 열린데이터광장 요청당 최대 행 수 */
    private static final int PAGE_SIZE = 1_000;
    /** 조회 상한 페이지 — 5,000개. 서울 대여소는 약 2,700개 */
    private static final int MAX_PAGES = 5;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final DdareungiStationFilter filter;
    private final AtomicReference<SnapshotCache> stationSnapshot = new AtomicReference<>();
    private final AtomicReference<Mono<StationSnapshot>> refreshInFlight = new AtomicReference<>();
    private final AtomicLong refreshBlockedUntilMs = new AtomicLong(0L);
    private final Counter snapshotCacheHitCounter;
    private final Counter snapshotCacheMissCounter;
    private final Counter staleFallbackCounter;
    private final Counter staleExpiredCounter;
    private final Counter emptyFallbackCounter;
    private final Counter refreshBackoffCounter;
    private final Counter refreshShortCircuitCounter;
    private final long snapshotCacheTtlMs;
    private final long requestTimeoutMs;
    private final long refreshBackoffMs;
    private final long maxStaleAgeMs;
    private final ExternalApiResilience.Guard guard;

    public DdareungiApiClient(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${ddareungi.api-key}") String apiKey,
            @Value("${ddareungi.base-url:http://openapi.seoul.go.kr:8088}") String baseUrl,
            @Value("${navigation.cache.ddareungi-snapshot-ttl-ms:30000}") long snapshotCacheTtlMs,
            @Value("${ddareungi.request-timeout-ms:5000}") long requestTimeoutMs,
            @Value("${ddareungi.refresh-backoff-ms:30000}") long refreshBackoffMs,
            @Value("${ddareungi.max-stale-age-ms:900000}") long maxStaleAgeMs,
            MeterRegistry meterRegistry,
            ExternalApiResilience resilience
    ) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.snapshotCacheTtlMs = snapshotCacheTtlMs;
        this.requestTimeoutMs = requestTimeoutMs;
        this.refreshBackoffMs = refreshBackoffMs;
        this.maxStaleAgeMs = maxStaleAgeMs;
        this.guard = resilience.forApi("ddareungi");
        this.webClient = WebClients.withTimeout(webClientBuilder, baseUrl, requestTimeoutMs);
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
        this.staleFallbackCounter = fallbackCounter(meterRegistry, "stale_snapshot");
        this.staleExpiredCounter = fallbackCounter(meterRegistry, "stale_expired");
        this.emptyFallbackCounter = fallbackCounter(meterRegistry, "empty_snapshot");
        this.refreshBackoffCounter = fallbackCounter(meterRegistry, "refresh_backoff");
        this.refreshShortCircuitCounter = fallbackCounter(meterRegistry, "refresh_short_circuit");
    }

    private static Counter fallbackCounter(MeterRegistry meterRegistry, String reason) {
        return meterRegistry.counter("navigation.ddareungi.fallback.total", "reason", reason);
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

        /** snapshot 자체를 확보하지 못함 — "반경 내 정류소 없음" 과 다른 상태다. */
        public boolean isUnavailable() {
            return source == AvailabilitySource.EMPTY;
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

            Mono<StationSnapshot> loader = fetchStationsFrom(1)
                    .map(this::storeSnapshot)
                    // T-3: Retry 는 페이지 단위(fetchPage), CircuitBreaker 는 갱신 1회 단위 → 최종 onErrorResume 에서 stale snapshot fallback
                    .as(guard::circuitBreak)
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

    /**
     * {@code page} 부터 끝까지 이어 받는다. 페이지가 가득 차 있으면(행 수 = PAGE_SIZE) 다음 페이지가 있다고 본다.
     * 첫 페이지부터 0개면 실패로 올린다.
     */
    private Mono<List<DdareungiStation>> fetchStationsFrom(int page) {
        return fetchPage(page).flatMap(response -> {
            List<DdareungiStation> stations = response.toStations();
            if (page == 1 && response.rowCount() == 0) {
                return Mono.error(ExternalApiException.of("따릉이", 500, "정류소 0개 응답"));
            }
            if (response.rowCount() < PAGE_SIZE) {
                return Mono.just(stations);
            }
            if (page >= MAX_PAGES) {
                log.warn("[따릉이 API] 조회 상한 {}페이지({}개)까지 가득 참 — 초과분은 조회되지 않는다", MAX_PAGES, MAX_PAGES * PAGE_SIZE);
                return Mono.just(stations);
            }
            return fetchStationsFrom(page + 1)
                    .map(rest -> Stream.concat(stations.stream(), rest.stream()).toList());
        });
    }

    private Mono<DdareungiStationResponse> fetchPage(int page) {
        int startIndex = (page - 1) * PAGE_SIZE + 1;
        int endIndex = page * PAGE_SIZE;
        return webClient.get()
                .uri("/{apiKey}/json/bikeList/{start}/{end}/", apiKey, startIndex, endIndex)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> ExternalApiException.from("따릉이", response))
                // 인증키 오류는 XML 로 내려와 record 디코딩이 깨진다 — 문자열로 받아 직접 판별한다
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .map(this::parse)
                .as(guard::retry);
    }

    private DdareungiStationResponse parse(String body) {
        try {
            return objectMapper.readValue(body, DdareungiStationResponse.class).requireSuccess();
        } catch (JsonProcessingException e) {
            throw DdareungiStationResponse.nonJsonError(body);
        }
    }

    private StationSnapshot storeSnapshot(List<DdareungiStation> stations) {
        long now = System.currentTimeMillis();
        StationSnapshot snapshot = StationSnapshot.live(stations);
        stationSnapshot.set(new SnapshotCache(snapshot, now, now + snapshotCacheTtlMs));
        log.info("[따릉이 API] 서울시 전체 정류소 {}개 snapshot 갱신", stations.size());
        return snapshot;
    }

    private Mono<StationSnapshot> staleOrEmptySnapshot(SnapshotCache cached, String reason) {
        long now = System.currentTimeMillis();
        if (cached == null) {
            emptyFallbackCounter.increment();
            log.warn("[따릉이 API] 사용 가능한 snapshot 없음 -> 빈 결과 반환 (reason={})", reason);
            return Mono.just(StationSnapshot.empty());
        }
        if (cached.isTooOld(now, maxStaleAgeMs)) {
            staleExpiredCounter.increment();
            log.warn("[따릉이 API] stale snapshot 이 {}초 전 조회라 재사용 한도({}초)를 넘음 -> 빈 결과 반환 (reason={})",
                    cached.ageSeconds(now), maxStaleAgeMs / 1000, reason);
            return Mono.just(StationSnapshot.empty());
        }
        staleFallbackCounter.increment();
        log.warn("[따릉이 API] stale snapshot 재사용 ({}초 전 조회, reason={})", cached.ageSeconds(now), reason);
        return Mono.just(cached.snapshot().asStale());
    }

    private record SnapshotCache(StationSnapshot snapshot, long fetchedAtMs, long expiresAtMs) {
        private Mono<StationSnapshot> snapshotMono() {
            return Mono.just(snapshot);
        }

        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }

        private boolean isTooOld(long nowMs, long maxAgeMs) {
            return nowMs - fetchedAtMs >= maxAgeMs;
        }

        private long ageSeconds(long nowMs) {
            return (nowMs - fetchedAtMs) / 1000;
        }
    }
}
