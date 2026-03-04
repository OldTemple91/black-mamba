package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class DdareungiApiClient {

    private static final String BASE_URL = "http://openapi.seoul.go.kr:8088";
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final WebClient webClient;
    private final String apiKey;

    public DdareungiApiClient(
            WebClient.Builder webClientBuilder,
            @Value("${ddareungi.api-key}") String apiKey
    ) {
        this.apiKey = apiKey;
        this.webClient = webClientBuilder != null
                ? webClientBuilder.baseUrl(BASE_URL).build()
                : WebClient.builder().baseUrl(BASE_URL).build();
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
                .map(response -> filterNearby(response.toStations(), lat, lng, radiusMeters));
    }

    List<DdareungiStation> filterNearby(List<DdareungiStation> stations,
                                         double lat, double lng, int radiusMeters) {
        return stations.stream()
                .filter(s -> s.availableCount() > 0)
                .filter(s -> distanceMeters(lat, lng, s.lat(), s.lng()) <= radiusMeters)
                .toList();
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
