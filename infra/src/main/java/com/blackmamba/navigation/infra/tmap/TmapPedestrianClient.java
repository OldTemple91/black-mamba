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

import java.util.Map;
import java.util.Optional;

/**
 * TMAP 보행자 경로 API 클라이언트.
 * 실제 도로 거리(m)를 반환해 자전거/킥보드 이동 시간 추정 정확도를 높인다.
 * 실패 시 Optional.empty() 반환 → MobilityTimeAdapter에서 haversine fallback.
 */
@Component
public class TmapPedestrianClient {

    private static final String BASE_URL = "https://apis.openapi.sk.com";
    private static final Logger log = LoggerFactory.getLogger(TmapPedestrianClient.class);

    private final WebClient webClient;
    private final String appKey;
    private final Counter fallbackCounter;

    public TmapPedestrianClient(
            WebClient.Builder webClientBuilder,
            @Value("${tmap.app-key}") String appKey,
            MeterRegistry meterRegistry
    ) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.appKey = appKey;
        this.fallbackCounter = meterRegistry.counter(
                "navigation.tmap.fallback.total", "reason", "error");
    }

    /**
     * 두 지점 간 보행자 경로의 실제 도로 거리(m) 반환.
     * API 실패 또는 경로 없을 경우 Optional.empty() 반환.
     */
    public Mono<Optional<Integer>> getRoadDistanceMeters(Location origin, Location destination) {
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
                        log.debug("[TMAP] 도로 거리 {}m ({} → {})",
                                dist, origin.name(), destination.name());
                        return Optional.of(dist);
                    }
                    log.warn("[TMAP] 거리 0 반환 — 경로 없음 ({} → {})",
                            origin.name(), destination.name());
                    return Optional.<Integer>empty();
                })
                .onErrorResume(ex -> {
                    fallbackCounter.increment();
                    log.warn("[TMAP] 보행자 경로 조회 실패 → haversine fallback. 원인: {}", ex.getMessage());
                    return Mono.just(Optional.empty());
                });
    }
}
