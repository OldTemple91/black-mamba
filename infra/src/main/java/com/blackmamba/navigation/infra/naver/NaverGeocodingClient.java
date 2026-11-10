package com.blackmamba.navigation.infra.naver;

import com.blackmamba.navigation.infra.common.http.ExternalApiException;
import com.blackmamba.navigation.infra.common.http.WebClients;
import com.blackmamba.navigation.infra.naver.dto.NaverGeocodingResponse;
import com.blackmamba.navigation.infra.common.resilience.ExternalApiResilience;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * NCP 지오코딩 클라이언트.
 * <p>
 * 실패는 빈 결과로 정규화하되 <b>조용히</b> 하지 않는다 — 로그와 카운터를 남긴다.
 * 예전엔 {@code onErrorReturn} 만 있어 시크릿 회전·5xx 가 전부 "장소를 못 찾았습니다"(400) 로 사용자 탓이 됐다.
 */
/**
 * 네이버 클라우드 Maps 의 Geocoding 클라이언트.
 * <p>
 * 호스트는 신규 Maps 서비스({@code maps.apigw.ntruss.com})다. 예전 AI·NAVER API 호스트
 * ({@code naveropenapi.apigw.ntruss.com})는 2026-11-24 서비스 종료 예정이고, 그 전에 이미
 * {@code 401 "A subscription to the API is required"} 로 거절되고 있었다. 경로·헤더·응답 형식은 같다.
 */
@Component
public class NaverGeocodingClient {

    private static final Logger log = LoggerFactory.getLogger(NaverGeocodingClient.class);
    private static final String API_NAME = "Naver Geocoding";

    private final WebClient webClient;
    private final Counter geocodeErrorCounter;
    private final Counter suggestErrorCounter;
    private final ExternalApiResilience.Guard guard;

    public NaverGeocodingClient(
            WebClient.Builder builder,
            @Value("${naver.client-id}") String clientId,
            @Value("${naver.client-secret}") String clientSecret,
            @Value("${naver.geocoding.base-url:https://maps.apigw.ntruss.com}") String baseUrl,
            @Value("${naver.request-timeout-ms:3000}") long requestTimeoutMs,
            MeterRegistry meterRegistry,
            ExternalApiResilience resilience
    ) {
        this.webClient = WebClients.withTimeout(
                builder.defaultHeader("X-NCP-APIGW-API-KEY-ID", clientId)
                        .defaultHeader("X-NCP-APIGW-API-KEY", clientSecret),
                baseUrl, requestTimeoutMs);
        this.guard = resilience.forApi("naver-geocoding");
        this.geocodeErrorCounter = meterRegistry.counter("navigation.naver.fallback.total", "api", "geocode", "reason", "error");
        this.suggestErrorCounter = meterRegistry.counter("navigation.naver.fallback.total", "api", "suggest", "reason", "error");
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
                .onStatus(HttpStatusCode::isError, response -> ExternalApiException.from(API_NAME, response))
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
                .as(guard::protect)
                .onErrorResume(e -> {
                    geocodeErrorCounter.increment();
                    log.warn("[Naver] 지오코딩 실패(최종) → 빈 결과. query=\"{}\" err={}", query, e.toString());
                    return Mono.just(Optional.empty());
                });
    }

    /**
     * 장소명 연관검색어 (NCP Geocoding API 결과를 SuggestItem 목록으로 변환)
     * 최대 5개 반환
     */
    public Mono<List<SuggestItem>> suggest(String query) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/map-geocode/v2/geocode")
                        .queryParam("query", query)
                        .queryParam("count", 5)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> ExternalApiException.from(API_NAME, response))
                .bodyToMono(NaverGeocodingResponse.class)
                .map(response -> {
                    if (!"OK".equals(response.status())
                            || response.addresses() == null
                            || response.addresses().isEmpty()) {
                        return List.<SuggestItem>of();
                    }
                    return response.addresses().stream()
                            .map(addr -> {
                                String name = (addr.roadAddress() != null && !addr.roadAddress().isBlank())
                                        ? addr.roadAddress() : addr.jibunAddress();
                                return new SuggestItem(name != null ? name : query, addr.lat(), addr.lng());
                            })
                            .toList();
                })
                .as(guard::protect)
                .onErrorResume(e -> {
                    suggestErrorCounter.increment();
                    log.warn("[Naver] 자동완성 실패(최종) → 빈 결과. query=\"{}\" err={}", query, e.toString());
                    return Mono.just(List.of());
                });
    }

    public record SuggestItem(String name, double lat, double lng) {}
}
