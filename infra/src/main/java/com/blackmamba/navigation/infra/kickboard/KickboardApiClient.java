package com.blackmamba.navigation.infra.kickboard;

import com.blackmamba.navigation.infra.kickboard.dto.KickboardDevice;
import com.blackmamba.navigation.infra.kickboard.dto.KickboardResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class KickboardApiClient {

    private static final String BASE_URL = "http://apis.data.go.kr/1613000/PersonalMobilityInfoService";

    private final WebClient webClient;
    private final String apiKey;
    private final KickboardDeviceFilter filter;

    public KickboardApiClient(
            WebClient.Builder webClientBuilder,
            @Value("${tago.api-key}") String apiKey
    ) {
        this.apiKey = apiKey;
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.filter = new KickboardDeviceFilter();
    }

    public Mono<List<KickboardDevice>> getNearbyDevices(double lat, double lng, int radiusMeters) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/getPMProvider")
                        .queryParam("serviceKey", apiKey)
                        .queryParam("region", "서울")
                        .queryParam("numOfRows", 1000)
                        .build())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(
                                        new RuntimeException("TAGO API 오류: " + response.statusCode() + " " + body)
                                ))
                )
                .bodyToMono(KickboardResponse.class)
                .map(response -> filter.filterNearby(response.toDevices(), lat, lng, radiusMeters));
    }
}
