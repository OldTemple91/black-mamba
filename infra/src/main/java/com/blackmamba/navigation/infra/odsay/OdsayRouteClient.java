package com.blackmamba.navigation.infra.odsay;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.infra.odsay.dto.OdsayRouteResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class OdsayRouteClient {

    private static final String BASE_URL = "https://api.odsay.com/v1/api";
    private static final Logger log = LoggerFactory.getLogger(OdsayRouteClient.class);

    private final WebClient webClient;
    private final OdsayRouteMapper mapper;
    private final String apiKey;
    private final Counter transitRouteErrorFallbackCounter;
    private final Counter transitTimeEstimateFallbackCounter;

    public OdsayRouteClient(
            WebClient.Builder webClientBuilder,
            OdsayRouteMapper mapper,
            @Value("${odsay.api-key}") String apiKey,
            MeterRegistry meterRegistry
    ) {
        this.mapper = mapper;
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

    public Mono<List<Leg>> getTransitRoute(Location origin, Location destination) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/searchPubTransPathT")
                        .queryParam("apiKey", apiKey)
                        .queryParam("SX", origin.lng())
                        .queryParam("SY", origin.lat())
                        .queryParam("EX", destination.lng())
                        .queryParam("EY", destination.lat())
                        .build())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(
                                        new RuntimeException("ODsay API 오류: " + response.statusCode() + " " + body)
                                ))
                )
                .bodyToMono(OdsayRouteResponse.class)
                .map(response -> {
                    if (response.result() == null) {
                        return List.<Leg>of();
                    }
                    var paths = response.result().path();
                    if (paths == null || paths.isEmpty()) {
                        return List.<Leg>of();
                    }
                    return mapper.toLegs(paths.get(0));
                })
                .onErrorResume(ex -> {
                    transitRouteErrorFallbackCounter.increment();
                    log.warn("ODsay transit route fallback: {}", ex.getMessage());
                    return Mono.just(List.of());
                });
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
