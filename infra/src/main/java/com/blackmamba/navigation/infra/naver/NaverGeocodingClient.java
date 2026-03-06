package com.blackmamba.navigation.infra.naver;

import com.blackmamba.navigation.infra.naver.dto.NaverGeocodingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Component
public class NaverGeocodingClient {

    private static final String BASE_URL = "https://naveropenapi.apigw.ntruss.com";

    private final WebClient webClient;

    public NaverGeocodingClient(
            WebClient.Builder builder,
            @Value("${naver.client-id}") String clientId,
            @Value("${naver.client-secret}") String clientSecret
    ) {
        this.webClient = builder
                .baseUrl(BASE_URL)
                .defaultHeader("X-NCP-APIGW-API-KEY-ID", clientId)
                .defaultHeader("X-NCP-APIGW-API-KEY", clientSecret)
                .build();
    }

    /**
     * 장소명 → 좌표 변환 (NCP Geocoding REST API)
     *
     * @return lat/lng 쌍, 결과 없으면 Optional.empty()
     */
    public Mono<Optional<double[]>> geocode(String query) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/map-geocode/v2/geocode")
                        .queryParam("query", query)
                        .build())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(
                                        new RuntimeException("Naver Geocoding API 오류: " + response.statusCode() + " " + body)
                                ))
                )
                .bodyToMono(NaverGeocodingResponse.class)
                .map(response -> {
                    if (!"OK".equals(response.status())
                            || response.addresses() == null
                            || response.addresses().isEmpty()) {
                        return Optional.<double[]>empty();
                    }
                    var addr = response.addresses().get(0);
                    return Optional.of(new double[]{addr.lat(), addr.lng()});
                })
                .onErrorReturn(Optional.empty());
    }
}
