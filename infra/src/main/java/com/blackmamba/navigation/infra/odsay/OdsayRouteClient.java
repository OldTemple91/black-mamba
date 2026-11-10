package com.blackmamba.navigation.infra.odsay;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.TransitSearchDistancePolicy;
import com.blackmamba.navigation.infra.common.GeohashKeyGenerator;
import com.blackmamba.navigation.infra.common.cache.TtlCache;
import com.blackmamba.navigation.infra.common.cache.TtlCacheRegistry;
import com.blackmamba.navigation.infra.common.http.ExternalApiException;
import com.blackmamba.navigation.infra.common.http.ExternalApiJson;
import com.blackmamba.navigation.infra.common.http.WebClients;
import com.blackmamba.navigation.infra.odsay.dto.OdsayRouteResponse;
import com.blackmamba.navigation.infra.common.resilience.ExternalApiResilience;
import tools.jackson.core.JacksonException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OdsayRouteClient {

    private static final Logger log = LoggerFactory.getLogger(OdsayRouteClient.class);

    private static final long RATE_INTERVAL_MS = 200L; // max 5 req/sec
    private final WebClient webClient;
    private final OdsayRouteMapper mapper;
    private final String apiKey;
    private final Counter transitRouteErrorFallbackCounter;
    private final Counter transitRouteEmptyResultCounter;
    private final Counter transitTimeEstimateFallbackCounter;
    private final Counter transitRouteShortDistanceSkipCounter;
    private final Counter transitTimeShortDistanceSkipCounter;
    private final AtomicLong nextPermitMs = new AtomicLong(0);
    // B-3: Geohash 기반 키 → 150m 격자 내 좌표는 동일 키로 캐시 공유
    private final TtlCache<String, List<Leg>> routeCache;
    private final ExternalApiResilience.Guard guard;

    public OdsayRouteClient(
            WebClient.Builder webClientBuilder,
            OdsayRouteMapper mapper,
            @Value("${odsay.api-key}") String apiKey,
            @Value("${odsay.base-url:https://api.odsay.com/v1/api}") String baseUrl,
            @Value("${navigation.cache.odsay-route-ttl-ms:300000}") long routeCacheTtlMs,
            @Value("${odsay.request-timeout-ms:5000}") long requestTimeoutMs,
            MeterRegistry meterRegistry,
            ExternalApiResilience resilience,
            TtlCacheRegistry cacheRegistry
    ) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.routeCache = cacheRegistry.create("odsay_route", routeCacheTtlMs);
        this.webClient = WebClients.withTimeout(webClientBuilder, baseUrl, requestTimeoutMs);
        this.guard = resilience.forApi("odsay");
        this.transitRouteErrorFallbackCounter = meterRegistry.counter(
                "navigation.odsay.fallback.total",
                "type", "transit_route",
                "reason", "error"
        );
        // 200 응답이지만 result/path 가 비어 있음 — API 키 오류·경로 없음. 오류 카운터와 분리해 "조용한 실패" 를 보이게 한다
        this.transitRouteEmptyResultCounter = meterRegistry.counter(
                "navigation.odsay.fallback.total",
                "type", "transit_route",
                "reason", "empty_result"
        );
        this.transitTimeEstimateFallbackCounter = meterRegistry.counter(
                "navigation.odsay.fallback.total",
                "type", "transit_time",
                "reason", "empty_or_zero"
        );
        this.transitRouteShortDistanceSkipCounter = meterRegistry.counter(
                "navigation.odsay.fallback.total",
                "type", "transit_route",
                "reason", "short_distance"
        );
        this.transitTimeShortDistanceSkipCounter = meterRegistry.counter(
                "navigation.odsay.fallback.total",
                "type", "transit_time",
                "reason", "short_distance"
        );
    }

    /**
     * ODsay 무료 요금제 rate limit 대응.
     * 동시 다발 요청 시 호출 간격을 RATE_INTERVAL_MS 이상으로 보장한다.
     */
    private Mono<Void> rateLimit() {
        return Mono.defer(() -> {
            long now = System.currentTimeMillis();
            long scheduled = nextPermitMs.accumulateAndGet(now,
                    (prev, cur) -> Math.max(prev, cur) + RATE_INTERVAL_MS);
            long delayMs = scheduled - RATE_INTERVAL_MS - now;
            return delayMs > 0
                    ? Mono.delay(Duration.ofMillis(delayMs)).then()
                    : Mono.empty();
        });
    }

    @Observed(name = "external.api.odsay.route",
            contextualName = "ODsay 대중교통 경로 조회",
            lowCardinalityKeyValues = {"external.api", "odsay"})
    public Mono<List<Leg>> getTransitRoute(Location origin, Location destination) {
        if (TransitSearchDistancePolicy.isTooCloseForTransit(origin, destination)) {
            transitRouteShortDistanceSkipCounter.increment();
            log.info("[ODsay] 직선거리 {}m 이하 구간은 호출 생략 ({} -> {})",
                    (int) TransitSearchDistancePolicy.MIN_TRANSIT_DISTANCE_METERS, origin.name(), destination.name());
            return Mono.just(List.of());
        }
        String key = GeohashKeyGenerator.forRoute(origin, destination);
        // 빈 결과(오류 폴백·키 오류)는 캐시하지 않는다 — 일시 장애가 30초 동안 모든 경로를 추정값으로 고정하지 않게
        return routeCache.get(key, () -> requestTransitRoute(origin, destination), legs -> !legs.isEmpty());
    }

    private Mono<List<Leg>> requestTransitRoute(Location origin, Location destination) {
        return Mono.defer(() -> rateLimit().then(webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/searchPubTransPathT")
                        .queryParam("apiKey", "{apiKey}")
                        .queryParam("SX", origin.lng())
                        .queryParam("SY", origin.lat())
                        .queryParam("EX", destination.lng())
                        .queryParam("EY", destination.lat())
                        .build(apiKey))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> ExternalApiException.from("ODsay", response))
                .bodyToMono(String.class)
                .flatMap(rawJson -> {
                    log.debug("[ODsay] raw 응답 (첫 500자): {}", rawJson.length() > 500 ? rawJson.substring(0, 500) : rawJson);
                    OdsayRouteResponse response;
                    try {
                        response = ExternalApiJson.MAPPER.readValue(rawJson, OdsayRouteResponse.class);
                    } catch (JacksonException ex) {
                        // 응답이 JSON 이 아니면 상대 쪽 문제 — 예외로 올려야 재시도·회로가 반응한다
                        log.error("[ODsay] JSON 파싱 실패: {} — raw={}", ex.getOriginalMessage(), rawJson);
                        return Mono.error(ExternalApiException.of("ODsay", 500, "JSON 파싱 실패: " + ex.getOriginalMessage()));
                    }
                    var apiError = response.findApiError();
                    if (apiError.isPresent()) {
                        OdsayRouteResponse.ApiError error = apiError.get();
                        if (error.isNoRoute()) {
                            transitRouteEmptyResultCounter.increment();
                            log.info("[ODsay] 경로 없음 응답 (code={} {})", error.code(), error.message());
                            return Mono.just(List.<Leg>of());
                        }
                        if (error.isBadRequest()) {
                            log.error("[ODsay] 요청 형식 오류 — 호출 코드의 버그 (code={} {}) {} → {}", error.code(), error.message(), origin, destination);
                        }
                        // 200 OK 로 내려오는 서버·키·한도 오류 — 예외여야 회로 차단기가 실패로 집계하고 조용한 추정 경로 대체가 멈춘다.
                        // 서버 오류(500)만 재시도 대상이다
                        int status = 400;
                        if (error.isServerError()) {
                            status = 500;
                        }
                        return Mono.error(ExternalApiException.of("ODsay", status, "HTTP 200 오류 봉투 code=" + error.code() + " " + error.message()));
                    }
                    if (response.result() == null) {
                        transitRouteEmptyResultCounter.increment();
                        log.warn("[ODsay] result 없음 — 경로 없음으로 처리. raw={}", rawJson);
                        return Mono.just(List.<Leg>of());
                    }
                    var paths = response.result().path();
                    if (paths == null || paths.isEmpty()) {
                        transitRouteEmptyResultCounter.increment();
                        log.warn("[ODsay] 경로(path) 배열 비어있음");
                        return Mono.just(List.<Leg>of());
                    }
                    List<Leg> legs = normalizeWalkLegs(mapper.toLegs(paths.get(0)), origin, destination);
                    log.debug("[ODsay] 파싱 성공 — leg {}개", legs.size());
                    return Mono.just(legs);
                })))
                // 호출 간격 제한은 Retry 안쪽에 둔다 — 재시도도 간격을 지키고, 회로가 열려 있으면 대기 없이 바로 거절된다.
                // (예전엔 limiter 가 바깥이라 재시도는 간격 없이 나가고, 열린 회로도 대기를 다 채운 뒤에야 거절했다)
                .as(guard::protect)
                .onErrorResume(ex -> {
                    transitRouteErrorFallbackCounter.increment();
                    if (isExpectedFailure(ex)) {
                        log.warn("[ODsay] 호출 실패(최종) → 빈 리스트 반환. 원인: {}", ex.toString());
                    } else {
                        // 매퍼 NPE 같은 우리 쪽 버그 — 스택 없이 WARN 한 줄로 묻지 않는다
                        log.error("[ODsay] 응답 처리 중 예기치 못한 오류 → 빈 리스트 반환", ex);
                    }
                    return Mono.just(List.of());
                });
    }

    /**
     * 외부 사정으로 생기는 실패 — 오류 응답, 연결 거부·타임아웃, 열린 회로의 거절.
     * 이것들까지 스택과 함께 ERROR 로 남기면 장애 중에 후보 허브 수만큼 스택이 쏟아져 진짜 버그 로그가 묻힌다.
     */
    private static boolean isExpectedFailure(Throwable ex) {
        return ex instanceof ExternalApiException
                || ex instanceof WebClientException
                || ex instanceof TimeoutException
                || ex instanceof CallNotPermittedException;
    }

    private List<Leg> normalizeWalkLegs(List<Leg> legs, Location origin, Location destination) {
        List<Leg> normalized = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            if (leg.isWalk() && (leg.start() == null || leg.end() == null)) {
                Location start = leg.start() != null ? leg.start() : findPreviousEndpoint(legs, i, origin);
                Location end = leg.end() != null ? leg.end() : findNextStartpoint(legs, i, destination);
                normalized.add(new Leg(
                        leg.type(),
                        leg.mode(),
                        leg.durationMinutes(),
                        leg.distanceMeters(),
                        start,
                        end,
                        leg.transitInfo(),
                        leg.mobilityInfo(),
                        leg.routeCoordinates()
                ));
                continue;
            }
            normalized.add(leg);
        }
        return normalized.stream()
                .filter(leg -> !isDegenerateWalk(leg))
                .toList();
    }

    private boolean isDegenerateWalk(Leg leg) {
        if (!leg.isWalk()) return false;
        if (leg.durationMinutes() <= 0 || leg.distanceMeters() <= 0) return true;
        if (leg.start() == null || leg.end() == null) return false;
        return Double.compare(leg.start().lat(), leg.end().lat()) == 0
                && Double.compare(leg.start().lng(), leg.end().lng()) == 0;
    }

    private Location findPreviousEndpoint(List<Leg> legs, int index, Location fallback) {
        for (int i = index - 1; i >= 0; i--) {
            if (legs.get(i).end() != null) return legs.get(i).end();
        }
        return fallback;
    }

    private Location findNextStartpoint(List<Leg> legs, int index, Location fallback) {
        for (int i = index + 1; i < legs.size(); i++) {
            if (legs.get(i).start() != null) return legs.get(i).start();
        }
        return fallback;
    }

    @Observed(name = "external.api.odsay.time",
            contextualName = "ODsay 소요시간 조회",
            lowCardinalityKeyValues = {"external.api", "odsay"})
    public Mono<Integer> getTransitTimeMinutes(Location origin, Location destination) {
        if (TransitSearchDistancePolicy.isTooCloseForTransit(origin, destination)) {
            transitTimeShortDistanceSkipCounter.increment();
            return Mono.just(haversineTransitEstimate(origin, destination));
        }
        return getTransitRoute(origin, destination)
                .map(legs -> {
                    int sum = legs.stream().mapToInt(Leg::durationMinutes).sum();
                    // ODsay가 빈 결과 반환 시 직선거리 기반 추정값 사용
                    if (sum > 0) {
                        return sum;
                    }
                    transitTimeEstimateFallbackCounter.increment();
                    return haversineTransitEstimate(origin, destination);
                });
    }

    /** ODsay 실패 시 대중교통 소요 시간 추정 (직선거리 × 1.4 우회계수 ÷ 25 km/h) */
    static int haversineTransitEstimate(Location a, Location b) {
        double distKm = GeoDistance.kilometers(a, b);
        return Math.max((int) Math.ceil(distKm * 1.4 / 25.0 * 60), 5);
    }
}
