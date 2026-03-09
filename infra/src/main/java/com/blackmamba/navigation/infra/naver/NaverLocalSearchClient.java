package com.blackmamba.navigation.infra.naver;

import com.blackmamba.navigation.infra.naver.dto.NaverLocalSearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 네이버 지역 검색 API 클라이언트
 * - Base URL: https://openapi.naver.com
 * - 인증: X-Naver-Client-Id / X-Naver-Client-Secret (NCP 키와 다른 developers.naver.com 키)
 * - POI 키워드 검색 지원 ("강남역", "스타벅스 강남" 등)
 *
 * NAVER_SEARCH_CLIENT_ID / NAVER_SEARCH_CLIENT_SECRET 환경변수가 없으면 비활성화(빈 목록 반환)
 */
@Component
public class NaverLocalSearchClient {

    private static final String BASE_URL = "https://openapi.naver.com";

    private final WebClient webClient;
    private final boolean enabled;

    public NaverLocalSearchClient(
            WebClient.Builder builder,
            @Value("${naver.search.client-id:}") String clientId,
            @Value("${naver.search.client-secret:}") String clientSecret
    ) {
        this.enabled = clientId != null && !clientId.isBlank();
        this.webClient = builder
                .baseUrl(BASE_URL)
                .defaultHeader("X-Naver-Client-Id", clientId != null ? clientId : "")
                .defaultHeader("X-Naver-Client-Secret", clientSecret != null ? clientSecret : "")
                .build();
    }

    /**
     * 장소 키워드 검색
     *
     * @param query   검색어 (예: "강남역", "홍대입구")
     * @param display 반환할 최대 결과 수 (최대 5 권장)
     * @return PlaceItem 목록 (한국 좌표 범위 밖이면 필터링됨)
     */
    public Mono<List<PlaceItem>> searchPlaces(String query, int display) {
        if (!enabled || query == null || query.isBlank()) {
            return Mono.just(List.of());
        }

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/search/local.json")
                        .queryParam("query", query)
                        .queryParam("display", display)
                        .queryParam("sort", "random")
                        .build())
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> Mono.error(new RuntimeException("Naver Local Search API 오류: " + response.statusCode()))
                )
                .bodyToMono(NaverLocalSearchResponse.class)
                .map(response -> {
                    if (response == null || response.items() == null) return List.<PlaceItem>of();
                    return response.items().stream()
                            .map(item -> {
                                try {
                                    double lng = parseCoord(item.mapx());
                                    double lat = parseCoord(item.mapy());
                                    if (!isValidKoreaCoord(lat, lng)) return null;
                                    return new PlaceItem(item.cleanTitle(), lat, lng);
                                } catch (Exception e) {
                                    return null;
                                }
                            })
                            .filter(p -> p != null)
                            .toList();
                })
                .onErrorReturn(List.of());
    }

    /**
     * mapx/mapy 좌표 파싱
     * 네이버 지역 검색 API는 경도·위도를 × 1e7 정수 문자열로 반환
     * (예: "1270442560" → 127.044256)
     */
    private static double parseCoord(String raw) {
        double v = Double.parseDouble(raw);
        return v > 1000 ? v / 1e7 : v;
    }

    /** 한국 좌표 범위 검증 (lat 33~38, lng 124~132) */
    private static boolean isValidKoreaCoord(double lat, double lng) {
        return lat >= 33 && lat <= 38 && lng >= 124 && lng <= 132;
    }

    public record PlaceItem(String name, double lat, double lng) {}
}
