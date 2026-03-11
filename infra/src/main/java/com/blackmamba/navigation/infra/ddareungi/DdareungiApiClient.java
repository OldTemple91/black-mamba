package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class DdareungiApiClient {

    private static final Logger log = LoggerFactory.getLogger(DdareungiApiClient.class);
    private static final String BASE_URL = "http://openapi.seoul.go.kr:8088";

    private final WebClient webClient;
    private final String apiKey;
    private final DdareungiStationFilter filter;

    public DdareungiApiClient(
            WebClient.Builder webClientBuilder,
            @Value("${ddareungi.api-key}") String apiKey
    ) {
        this.apiKey = apiKey;
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.filter = new DdareungiStationFilter();
    }

    public Mono<List<DdareungiStation>> getNearbyStations(double lat, double lng, int radiusMeters) {
        return webClient.get()
                .uri("/{apiKey}/json/bikeList/1/1000/", apiKey)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(
                                        new RuntimeException("따릉이 API 오류: " + response.statusCode() + " " + body)
                                ))
                )
                .bodyToMono(DdareungiStationResponse.class)
                .map(response -> {
                    List<DdareungiStation> all = response.toStations();
                    List<DdareungiStation> nearby = filter.filterNearby(all, lat, lng, radiusMeters);
                    log.info("[따릉이 API] 서울시 전체 정류소 {}개 → 반경 {}m 내 대여가능 {}개",
                            all.size(), radiusMeters, nearby.size());
                    return nearby;
                });
    }
}
