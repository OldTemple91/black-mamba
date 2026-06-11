package com.blackmamba.navigation.api.nlp;

import com.blackmamba.navigation.application.route.AccessibilityContext;
import com.blackmamba.navigation.application.route.RecommendationPreference;
import com.blackmamba.navigation.application.route.RouteNarrativeEnhancer;
import com.blackmamba.navigation.application.route.RouteOptimizationService;
import com.blackmamba.navigation.application.route.SearchMode;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.infra.naver.NaverGeocodingClient;
import com.blackmamba.navigation.infra.naver.NaverLocalSearchClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 자연어 경로 검색 엔드포인트 (RAG Phase 1).
 * <p>
 * 사용자 자연어 → LLM Intent 파싱 → 지오코딩 → 기존 RouteOptimizationService 호출.
 *
 * <h3>Tool Calling 구조 (간소화)</h3>
 * LLM은 Intent만 파싱한다. Geocoding과 Route Search 자체는 서버가 직접 호출하여
 * 성능과 비용을 보장한다. (향후 고도화 시 Spring AI ToolCallback 으로 확장 가능)
 */
@Tag(name = "자연어 경로 검색", description = "자연어 요청 → LLM 의도 파싱 → 경로 탐색")
@RestController
@RequestMapping("/api/nlp/routes")
public class NaturalLanguageRouteController {

    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageRouteController.class);
    private static final Duration ROUTE_SEARCH_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration GEOCODE_TIMEOUT = Duration.ofSeconds(5);
    /** LLM 프롬프트에 들어가는 입력 — 과도한 길이로 인한 자원 고갈/인젝션 방어 */
    private static final int MAX_QUERY_LENGTH = 200;

    private final NlpRouteIntentParser intentParser;
    private final NaverGeocodingClient geocodingClient;
    private final NaverLocalSearchClient localSearchClient;
    private final RouteOptimizationService routeOptimizationService;
    private final RouteNarrativeEnhancer narrativeEnhancer;  // RAG-4

    public NaturalLanguageRouteController(NlpRouteIntentParser intentParser,
                                          NaverGeocodingClient geocodingClient,
                                          NaverLocalSearchClient localSearchClient,
                                          RouteOptimizationService routeOptimizationService,
                                          RouteNarrativeEnhancer narrativeEnhancer) {
        this.intentParser = intentParser;
        this.geocodingClient = geocodingClient;
        this.localSearchClient = localSearchClient;
        this.routeOptimizationService = routeOptimizationService;
        this.narrativeEnhancer = narrativeEnhancer;
    }

    @Operation(
            summary = "자연어 경로 검색",
            description = """
                    자연어 요청을 LLM으로 파싱해 기존 경로 탐색 엔진을 호출합니다.

                    **예시:**
                    - "강남역에서 홍대입구까지" → 기본 경로
                    - "강남에서 홍대까지 빠르게" → TIME_PRIORITY
                    - "노인도 쉬운 강남에서 홍대 경로" → walkingSpeedKmh=3.0
                    - "휠체어로 갈 수 있는 강남→홍대" → wheelchairAccessible=true
                    - "따릉이로 강남에서 홍대" → mobility=[DDAREUNGI]
                    """
    )
    @GetMapping
    public ResponseEntity<Map<String, Object>> searchByNaturalLanguage(
            @Parameter(description = "자연어 경로 요청", example = "강남에서 홍대까지 환승 적은 경로")
            @RequestParam("q") String query
    ) {
        // Step 0: 입력 검증 — LLM 프롬프트로 들어가는 입력이므로 길이 상한 필수
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "QUERY_REQUIRED",
                    "message", "쿼리 파라미터 'q' 가 비어 있습니다."
            ));
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "QUERY_TOO_LONG",
                    "message", "쿼리는 " + MAX_QUERY_LENGTH + "자 이하여야 합니다."
            ));
        }
        log.info("[NLP] 자연어 요청 수신: \"{}\"", query);

        // Step 1: LLM 의도 파싱
        RouteSearchIntent intent;
        try {
            intent = intentParser.parse(query);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[NLP] 파싱 실패: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "NLP_PARSE_FAILED",
                    "message", e.getMessage(),
                    "query", query
            ));
        }

        // Step 2: 장소명 → 좌표 (Geocoding) — origin/destination 병렬 (직렬 시 최악 2배 지연)
        var resolved = Mono.zip(
                        resolveLocation(intent.origin()).map(Optional::of).defaultIfEmpty(Optional.empty()),
                        resolveLocation(intent.destination()).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .block(GEOCODE_TIMEOUT.multipliedBy(2));

        Location origin = resolved == null ? null : resolved.getT1().orElse(null);
        Location destination = resolved == null ? null : resolved.getT2().orElse(null);

        if (origin == null || destination == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "GEOCODING_FAILED",
                    "message", "출발지 또는 도착지를 찾지 못했습니다.",
                    "parsed", intent
            ));
        }

        // Step 3: 기존 경로 탐색 엔진 호출
        // LLM 출력은 스펙 밖 값을 낼 수 있으므로 valueOf 실패를 500 으로 흘리지 않는다.
        List<MobilityType> mobilityTypes = parseMobilityTypes(intent.mobility());
        SearchMode mode = mobilityTypes.isEmpty() ? SearchMode.OPTIMAL : SearchMode.SPECIFIC;
        RecommendationPreference preference = parsePreference(intent.preference());
        AccessibilityContext access = AccessibilityContext.of(intent.wheelchairAccessible(), intent.walkingSpeedKmh());

        List<Route> routes = routeOptimizationService
                .findRoutes(origin, destination, mobilityTypes, mode, preference, access)
                .block(ROUTE_SEARCH_TIMEOUT);

        // RAG-4: 추천 경로의 carComparison.narrative 를 LLM 설명으로 업그레이드
        if (routes != null && !routes.isEmpty()) {
            routes = narrativeEnhancer.enhanceRecommended(
                    routes, origin, destination, preference.name());
        }

        return ResponseEntity.ok(Map.of(
                "query", query,
                "parsedIntent", intent,
                "origin", Map.of("name", intent.origin(), "lat", origin.lat(), "lng", origin.lng()),
                "destination", Map.of("name", intent.destination(), "lat", destination.lat(), "lng", destination.lng()),
                "searchMode", mode.name(),
                "preference", preference.name(),
                "routes", routes != null ? routes : List.of()
        ));
    }

    /**
     * 장소명 → Location. 2단계 fallback (reactive — 호출부에서 origin/destination 병렬 zip):
     *   1) 네이버 지오코딩 (주소 정확 일치)
     *   2) 네이버 장소검색 (POI 검색, "강남역" 같은 역/상호 매칭)
     */
    private Mono<Location> resolveLocation(String placeName) {
        if (placeName == null || placeName.isBlank()) return Mono.empty();

        Mono<Location> byGeocode = geocodingClient.geocode(placeName)
                .filter(Optional::isPresent)
                .map(opt -> {
                    double[] latLng = opt.get();
                    return new Location(placeName, latLng[0], latLng[1]);
                });

        // Fallback: POI 장소 검색 (역/상호명 대응)
        Mono<Location> byPlaceSearch = Mono.defer(() -> localSearchClient.searchPlaces(placeName, 1)
                .filter(list -> !list.isEmpty())
                .map(list -> new Location(placeName, list.getFirst().lat(), list.getFirst().lng())));

        return byGeocode.switchIfEmpty(byPlaceSearch)
                .timeout(GEOCODE_TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("[NLP] 지오코딩 실패: \"{}\" — {}", placeName, e.getMessage());
                    return Mono.empty();
                });
    }

    /** LLM 이 스펙 밖 preference 를 반환해도 500 대신 기본값(RELIABILITY) 으로 폴백. */
    private static RecommendationPreference parsePreference(String raw) {
        if (raw == null || raw.isBlank()) return RecommendationPreference.RELIABILITY;
        try {
            return RecommendationPreference.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("[NLP] 알 수 없는 preference \"{}\" → RELIABILITY 폴백", raw);
            return RecommendationPreference.RELIABILITY;
        }
    }

    /** LLM 이 스펙 밖 mobility 를 반환하면 해당 값만 건너뛴다 (전체 요청 실패 방지). */
    private static List<MobilityType> parseMobilityTypes(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .filter(m -> m != null && !m.isBlank())
                .flatMap(m -> {
                    try {
                        return java.util.stream.Stream.of(MobilityType.valueOf(m));
                    } catch (IllegalArgumentException e) {
                        log.warn("[NLP] 알 수 없는 mobility \"{}\" 무시", m);
                        return java.util.stream.Stream.empty();
                    }
                })
                .toList();
    }
}
