package com.blackmamba.navigation.infra.kickboard;

import com.blackmamba.navigation.infra.kickboard.dto.KickboardDevice;
import com.blackmamba.navigation.infra.kickboard.dto.KickboardResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class KickboardApiClient {

    private static final Logger log = LoggerFactory.getLogger(KickboardApiClient.class);
    private static final String BASE_URL = "http://apis.data.go.kr/1613000/PersonalMobilityInfo";

    private final WebClient webClient;
    private final String apiKey;
    private final String cityCode;
    private final KickboardDeviceFilter filter;

    public KickboardApiClient(
            WebClient.Builder webClientBuilder,
            @Value("${tago.api-key}") String apiKey,
            @Value("${tago.city-code}") String cityCode
    ) {
        this.apiKey = apiKey;
        this.cityCode = cityCode;
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.filter = new KickboardDeviceFilter();
    }

    /**
     * GetPMListByProvider: 지역별 운영사기반 탑승가능 공유전동킥보드 목록 조회
     * - cityCode: 필수 (서울 코드 → application.yml tago.city-code)
     * - providerName: 생략 시 전체 운영사 조회
     * - _type=json: JSON 응답 강제
     */
    public Mono<List<KickboardDevice>> getNearbyDevices(double lat, double lng, int radiusMeters) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/GetPMListByProvider")
                        .queryParam("serviceKey", apiKey)
                        .queryParam("cityCode", cityCode)
                        .queryParam("numOfRows", 1000)
                        .queryParam("_type", "json")
                        .build())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(
                                        new RuntimeException("TAGO API 오류: " + response.statusCode() + " " + body)
                                ))
                )
                .bodyToMono(KickboardResponse.class)
                .map(response -> {
                    List<KickboardDevice> all = response.toDevices();
                    List<KickboardDevice> nearby = filter.filterNearby(all, lat, lng, radiusMeters);
                    log.info("[킥보드 API] 전체 기기 {}개 → 반경 {}m 내 배터리≥20% {}개",
                            all.size(), radiusMeters, nearby.size());
                    return nearby;
                });
    }
}
