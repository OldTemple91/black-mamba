package com.blackmamba.navigation.api.nlp;

import com.blackmamba.navigation.application.route.AccessibilityContext;
import com.blackmamba.navigation.application.route.RecommendationPreference;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;

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

    private final NlpRouteIntentParser intentParser;
    private final NaverGeocodingClient geocodingClient;
    private final NaverLocalSearchClient localSearchClient;
    private final RouteOptimizationService routeOptimizationService;

    public NaturalLanguageRouteController(NlpRouteIntentParser intentParser,
                                          NaverGeocodingClient geocodingClient,
                                          NaverLocalSearchClient localSearchClient,
                                          RouteOptimizationService routeOptimizationService) {
        this.intentParser = intentParser;
        this.geocodingClient = geocodingClient;
        this.localSearchClient = localSearchClient;
        this.routeOptimizationService = routeOptimizationService;
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

        // Step 2: 장소명 → 좌표 (Geocoding)
        Location origin = geocode(intent.origin());
        Location destination = geocode(intent.destination());

        if (origin == null || destination == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "GEOCODING_FAILED",
                    "message", "출발지 또는 도착지를 찾지 못했습니다.",
                    "parsed", intent
            ));
        }

        // Step 3: 기존 경로 탐색 엔진 호출
        List<MobilityType> mobilityTypes = (intent.mobility() == null) ? List.of() :
                intent.mobility().stream()
                        .filter(m -> m != null && !m.isBlank())
                        .map(MobilityType::valueOf)
                        .toList();
        SearchMode mode = mobilityTypes.isEmpty() ? SearchMode.OPTIMAL : SearchMode.SPECIFIC;
        RecommendationPreference preference = RecommendationPreference.valueOf(intent.preference());
        AccessibilityContext access = AccessibilityContext.of(intent.wheelchairAccessible(), intent.walkingSpeedKmh());

        List<Route> routes = routeOptimizationService
                .findRoutes(origin, destination, mobilityTypes, mode, preference, access)
                .block(ROUTE_SEARCH_TIMEOUT);

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
     * 장소명 → Location. 2단계 fallback:
     *   1) 네이버 지오코딩 (주소 정확 일치)
     *   2) 네이버 장소검색 (POI 검색, "강남역" 같은 역/상호 매칭)
     */
    private Location geocode(String placeName) {
        if (placeName == null || placeName.isBlank()) return null;

        Location byGeocode = geocodingClient.geocode(placeName)
                .blockOptional(Duration.ofSeconds(5))
                .flatMap(opt -> opt.map(latLng -> new Location(placeName, latLng[0], latLng[1])))
                .orElse(null);
        if (byGeocode != null) return byGeocode;

        // Fallback: POI 장소 검색 (역/상호명 대응)
        return localSearchClient.searchPlaces(placeName, 1)
                .blockOptional(Duration.ofSeconds(5))
                .filter(list -> !list.isEmpty())
                .map(list -> list.getFirst())
                .map(item -> new Location(placeName, item.lat(), item.lng()))
                .orElse(null);
    }
}
