package com.blackmamba.navigation.api.nlp;

import com.blackmamba.navigation.api.config.ErrorResponse;
import com.blackmamba.navigation.api.place.PlaceResponse;
import com.blackmamba.navigation.api.support.ReactorTimeouts;
import com.blackmamba.navigation.application.route.RouteIntent;
import com.blackmamba.navigation.application.route.RouteOptimizationService;
import com.blackmamba.navigation.application.route.RouteSearchQuery;
import com.blackmamba.navigation.application.route.SearchMode;
import com.blackmamba.navigation.application.route.accessibility.AccessibilityContext;
import com.blackmamba.navigation.application.route.history.RouteNarrativeEnhancer;
import com.blackmamba.navigation.application.route.weather.WeatherContext;
import com.blackmamba.navigation.application.route.port.PlaceResolverPort;
import com.blackmamba.navigation.application.route.port.RouteIntentParser;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Route;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 자연어 경로 검색 엔드포인트 (RAG Phase 1).
 * <p>
 * 사용자 자연어 → {@link RouteIntentParser}(LLM) → {@link PlaceResolverPort}(지오코딩)
 * → 기존 RouteOptimizationService 호출.
 *
 * <h3>Tool Calling 구조 (간소화)</h3>
 * LLM은 Intent만 파싱한다. Geocoding과 Route Search 자체는 서버가 직접 호출하여
 * 성능과 비용을 보장한다. (향후 고도화 시 Spring AI ToolCallback 으로 확장 가능)
 * 컨트롤러는 어떤 모델·지오코딩 벤더가 쓰이는지 모른다 — Port 만 안다.
 */
@Tag(name = "자연어 경로 검색", description = "자연어 요청 → LLM 의도 파싱 → 경로 탐색")
@RestController
@RequestMapping("/api/nlp/routes")
public class NaturalLanguageRouteController {

    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageRouteController.class);
    private static final Duration ROUTE_SEARCH_TIMEOUT = Duration.ofSeconds(45);
    /** 출발/도착 병렬 해석 상한 — 어댑터 자체 타임아웃(5s) 보다 넉넉히 */
    private static final Duration PLACE_RESOLVE_TIMEOUT = Duration.ofSeconds(10);
    /** LLM 프롬프트에 들어가는 입력 — 과도한 길이로 인한 자원 고갈/인젝션 방어 */
    private static final int MAX_QUERY_LENGTH = 200;

    private final RouteIntentParser intentParser;
    private final PlaceResolverPort placeResolver;
    private final RouteOptimizationService routeOptimizationService;
    private final RouteNarrativeEnhancer narrativeEnhancer;  // RAG-4

    public NaturalLanguageRouteController(RouteIntentParser intentParser,
                                          PlaceResolverPort placeResolver,
                                          RouteOptimizationService routeOptimizationService,
                                          RouteNarrativeEnhancer narrativeEnhancer) {
        this.intentParser = intentParser;
        this.placeResolver = placeResolver;
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
    public ResponseEntity<?> searchByNaturalLanguage(
            @Parameter(description = "자연어 경로 요청", example = "강남에서 홍대까지 환승 적은 경로")
            @RequestParam("q") String query
    ) {
        // Step 0: 입력 검증 — LLM 프롬프트로 들어가는 입력이므로 길이 상한 필수
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("QUERY_REQUIRED", "쿼리 파라미터 'q' 가 비어 있습니다."));
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("QUERY_TOO_LONG", "쿼리는 " + MAX_QUERY_LENGTH + "자 이하여야 합니다."));
        }
        log.info("[NLP] 자연어 요청 수신: \"{}\"", query);

        // Step 1: LLM 의도 파싱
        RouteIntent intent;
        try {
            intent = intentParser.parse(query);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[NLP] 파싱 실패: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("NLP_PARSE_FAILED", e.getMessage(), Map.of("query", query)));
        } catch (RuntimeException e) {
            // 모델이 답을 못 낸 게 아니라 모델에 닿지 못함(연결 거부·타임아웃)이 대부분 — 사용자 입력 탓(400)이 아니다.
            // 우리 쪽 버그도 이 분기로 오므로 스택을 남긴다
            log.error("[NLP] LLM 호출 실패 → 503", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("NLP_UNAVAILABLE", "자연어 검색을 일시적으로 사용할 수 없습니다. 출발지·도착지를 직접 선택해 검색해 주세요."));
        }

        // Step 2: 장소명 → 좌표 — origin/destination 병렬 (직렬 시 최악 2배 지연)
        Tuple2<Optional<Location>, Optional<Location>> resolved;
        try {
            resolved = Mono.zip(
                            placeResolver.resolve(intent.origin()),
                            placeResolver.resolve(intent.destination()))
                    .timeout(PLACE_RESOLVE_TIMEOUT)
                    .block();
        } catch (RuntimeException e) {
            if (!ReactorTimeouts.isTimeout(e)) {
                throw e;
            }
            log.error("[NLP] 장소 해석 타임아웃 ({}초 초과): {} → {}", PLACE_RESOLVE_TIMEOUT.getSeconds(), intent.origin(), intent.destination());
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ErrorResponse.of("GEOCODING_TIMEOUT", "장소 해석 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."));
        }

        Location origin = resolved == null ? null : resolved.getT1().orElse(null);
        Location destination = resolved == null ? null : resolved.getT2().orElse(null);

        if (origin == null || destination == null) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("GEOCODING_FAILED", "출발지 또는 도착지를 찾지 못했습니다.",
                            Map.of("parsed", intent)));
        }

        // Step 3: 기존 경로 탐색 엔진 호출
        SearchMode mode = intent.hasExplicitMobility() ? SearchMode.SPECIFIC : SearchMode.OPTIMAL;
        // /api/routes·/stream 과 같은 검증 규칙(좌표 범위·최소 거리) — 쿼리 생성자가 본다. 위반 시 400 + 사유 코드
        RouteSearchQuery searchQuery = new RouteSearchQuery(origin, destination, intent.mobility(), mode, intent.preference(),
                AccessibilityContext.of(intent.wheelchairAccessible(), intent.walkingSpeedKmh()), WeatherContext.DEFAULT);

        List<Route> routes;
        try {
            routes = routeOptimizationService
                    .findRoutes(searchQuery)
                    .timeout(ROUTE_SEARCH_TIMEOUT)
                    .block();
        } catch (RuntimeException e) {
            if (!ReactorTimeouts.isTimeout(e)) {
                throw e;  // 타임아웃이 아닌 오류는 전역 핸들러가 500 + 스택과 함께 기록한다
            }
            // /api/routes 와 같은 504 계약
            log.error("[NLP] 경로 탐색 타임아웃 ({}초 초과): {} → {}", ROUTE_SEARCH_TIMEOUT.getSeconds(), intent.origin(), intent.destination());
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ErrorResponse.of("SEARCH_TIMEOUT", "경로 탐색 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."));
        }

        // RAG-4: 추천 경로의 carComparison.narrative 를 LLM 설명으로 업그레이드
        if (routes != null && !routes.isEmpty()) {
            routes = narrativeEnhancer.enhanceRecommended(
                    routes, origin, destination, intent.preference());
        }

        return ResponseEntity.ok(new NaturalLanguageRoutesResponse(
                query,
                intent,
                PlaceResponse.from(origin),
                PlaceResponse.from(destination),
                mode.name(),
                intent.preference().name(),
                routes != null ? routes : List.of()
        ));
    }
}
