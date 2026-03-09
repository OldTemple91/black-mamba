package com.blackmamba.navigation.infra.odsay;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.infra.odsay.dto.OdsayRouteResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OdsayRouteClient {

    private static final String BASE_URL = "https://api.odsay.com/v1/api";
    private static final Logger log = LoggerFactory.getLogger(OdsayRouteClient.class);

    private static final long RATE_INTERVAL_MS = 200L; // max 5 req/sec

    private final WebClient webClient;
    private final OdsayRouteMapper mapper;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final Counter transitRouteErrorFallbackCounter;
    private final Counter transitTimeEstimateFallbackCounter;
    private final AtomicLong nextPermitMs = new AtomicLong(0);

    public OdsayRouteClient(
            WebClient.Builder webClientBuilder,
            OdsayRouteMapper mapper,
            ObjectMapper objectMapper,
            @Value("${odsay.api-key}") String apiKey,
            MeterRegistry meterRegistry
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.transitRouteErrorFallbackCounter = meterRegistry.counter(
                "navigation.odsay.fallback.total",
                "type", "transit_route",
                "reason", "error"
        );
        this.transitTimeEstimateFallbackCounter = meterRegistry.counter(
                "navigation.odsay.fallback.total",
                "type", "transit_time",
                "reason", "empty_or_zero"
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

    public Mono<List<Leg>> getTransitRoute(Location origin, Location destination) {
        return rateLimit().then(webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/searchPubTransPathT")
                        .queryParam("apiKey", "{apiKey}")
                        .queryParam("SX", origin.lng())
                        .queryParam("SY", origin.lat())
                        .queryParam("EX", destination.lng())
                        .queryParam("EY", destination.lat())
                        .build(apiKey))
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> {
                                    log.error("[ODsay] HTTP 오류 {} — body: {}", response.statusCode(), body);
                                    return Mono.error(new RuntimeException("ODsay API 오류: " + response.statusCode() + " " + body));
                                })
                )
                // ① raw JSON 문자열로 먼저 받아서 로그 찍기 (진단용)
                .bodyToMono(String.class)
                .flatMap(rawJson -> {
                    log.info("[ODsay] raw 응답 (첫 500자): {}", rawJson.length() > 500 ? rawJson.substring(0, 500) : rawJson);
                    try {
                        OdsayRouteResponse response = objectMapper.readValue(rawJson, OdsayRouteResponse.class);
                        if (response.result() == null) {
                            log.warn("[ODsay] result=null — API 키 오류 또는 경로 없음. raw={}", rawJson);
                            return Mono.just(List.<Leg>of());
                        }
                        var paths = response.result().path();
                        if (paths == null || paths.isEmpty()) {
                            log.warn("[ODsay] 경로(path) 배열 비어있음");
                            return Mono.just(List.<Leg>of());
                        }
                        List<Leg> legs = mapper.toLegs(paths.get(0));
                        log.info("[ODsay] 파싱 성공 — leg {}개: {}", legs.size(),
                                legs.stream().map(l ->
                                        l.type() + "(transitInfo=" + (l.transitInfo() != null
                                                ? l.transitInfo().lineName() + "/" + l.transitInfo().stationCount() + "역"
                                                : "null")
                                        + " start=" + (l.start() != null ? l.start().name() : "null") + ")"
                                ).toList());
                        return Mono.just(legs);
                    } catch (Exception ex) {
                        log.error("[ODsay] JSON 파싱 실패: {} — raw={}", ex.getMessage(), rawJson);
                        transitRouteErrorFallbackCounter.increment();
                        return Mono.just(List.<Leg>of());
                    }
                })
                .onErrorResume(ex -> {
                    transitRouteErrorFallbackCounter.increment();
                    log.warn("[ODsay] 호출 실패 → 빈 리스트 반환. 원인: {}", ex.getMessage());
                    return Mono.just(List.of());
                }));
    }

    public Mono<Integer> getTransitTimeMinutes(Location origin, Location destination) {
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
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double distKm = 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
        return Math.max((int) Math.ceil(distKm * 1.4 / 25.0 * 60), 5);
    }
}
