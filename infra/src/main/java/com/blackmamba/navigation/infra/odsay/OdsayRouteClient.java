package com.blackmamba.navigation.infra.odsay;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.infra.odsay.dto.OdsayRouteResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class OdsayRouteClient {

    private static final String BASE_URL = "https://api.odsay.com/v1/api";

    private final WebClient webClient;
    private final OdsayRouteMapper mapper;
    private final String apiKey;

    public OdsayRouteClient(
            OdsayRouteMapper mapper,
            @Value("${odsay.api-key}") String apiKey
    ) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.webClient = WebClient.builder().baseUrl(BASE_URL).build();
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
                .bodyToMono(OdsayRouteResponse.class)
                .map(response -> {
                    var paths = response.result().path();
                    if (paths == null || paths.isEmpty()) {
                        return List.<Leg>of();
                    }
                    return mapper.toLegs(paths.get(0));
                });
    }

    public Mono<Integer> getTransitTimeMinutes(Location origin, Location destination) {
        return getTransitRoute(origin, destination)
                .map(legs -> legs.stream().mapToInt(Leg::durationMinutes).sum());
    }
}
