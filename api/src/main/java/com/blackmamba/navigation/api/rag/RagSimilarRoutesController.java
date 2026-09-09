package com.blackmamba.navigation.api.rag;

import com.blackmamba.navigation.api.config.ErrorResponse;
import com.blackmamba.navigation.api.place.PlaceResponse;
import com.blackmamba.navigation.api.support.MobilityTypes;
import com.blackmamba.navigation.application.route.port.RagSearchRequest;
import com.blackmamba.navigation.application.route.port.RouteHistoryPort;
import com.blackmamba.navigation.application.route.port.ScoredRouteHistoryEntry;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import io.micrometer.observation.annotation.Observed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * RAG Phase 2 — 경로 이력 유사 검색 엔드포인트.
 *
 * <h3>3축 하이브리드 검색</h3>
 * <ul>
 *   <li>의미 유사도 (벡터): bge-m3 임베딩 + 코사인</li>
 *   <li>공간 필터 (payload): 출발/도착 좌표 → 저장소가 150m 격자로 색인 (AND)</li>
 *   <li>이동수단 필터 (payload): MobilityType OR</li>
 * </ul>
 * 품질 임계값({@code threshold}) 으로 무관한 쿼리는 빈 응답을 정직하게 준다.
 * 격자 인코딩 방식(geohash)은 저장소 어댑터의 구현 세부라 API 계약에 드러내지 않는다.
 *
 * <h3>관측성</h3>
 * {@code @Observed} 로 Tempo span 자동 생성 + Prometheus 메트릭 자동 집계.
 * 기존 프로젝트의 3축 관측성(Loki/Tempo/Prometheus) 과 일관.
 */
@Tag(name = "RAG 유사 경로", description = "벡터 DB 기반 의미 유사 경로 검색")
@RestController
@RequestMapping("/api/rag")
public class RagSimilarRoutesController {

    private static final Logger log = LoggerFactory.getLogger(RagSimilarRoutesController.class);
    private static final int MAX_TOP_K = 50;
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(15);

    private final RouteHistoryPort routeHistoryPort; // null 가능 (Qdrant 빈 부재 시)

    public RagSimilarRoutesController(ObjectProvider<RouteHistoryPort> portProvider) {
        this.routeHistoryPort = portProvider.getIfAvailable();
        if (this.routeHistoryPort == null) {
            log.warn("[RAG] RouteHistoryPort 빈 없음 — /api/rag/similar-routes 는 503 응답");
        }
    }

    @Operation(
            summary = "유사 경로 의미 검색 (하이브리드)",
            description = """
                    자연어 쿼리와 **의미적으로 유사한** 과거 경로 이력을 top-K 개 반환합니다.

                    **검색 축:**
                    - `q`: 자연어 의미 (필수)
                    - `originLat`/`originLng`, `destLat`/`destLng`: 출발/도착 좌표 필터 — 같은 150m 격자의 이력만 (AND)
                    - `mobility`: 이동수단 필터 — `DDAREUNGI`, `PERSONAL_EBIKE`, `PERSONAL_KICKBOARD` (OR, 콤마로 여러 개)
                    - `threshold`: 유사도 임계값 (0.0~1.0, 기본 0)

                    **예시:**
                    - `q=출퇴근 빠른 경로&mobility=DDAREUNGI`
                      → 따릉이 포함 + '출퇴근 빠른' 의미
                    - `q=빠른 경로&originLat=37.498&originLng=127.0276&threshold=0.5`
                      → 강남역 격자 출발 + 관련도 0.5 이상만
                    - `q=아무말&threshold=0.5`
                      → 결과가 없을 수 있음 (관련 경로 없음을 정직하게 응답)
                    """
    )
    @GetMapping("/similar-routes")
    @Observed(
            name = "navigation.rag.similar_search",
            contextualName = "RAG 유사 경로 검색",
            lowCardinalityKeyValues = {"component", "RagSimilarRoutesController"}
    )
    public ResponseEntity<?> findSimilarRoutes(
            @Parameter(description = "자연어 검색 쿼리", example = "출퇴근 빠른 경로", required = true)
            @RequestParam("q") String query,

            @Parameter(description = "반환 개수 (1~50, 기본 5)", example = "5")
            @RequestParam(value = "topK", defaultValue = "5") int topK,

            @Parameter(description = "유사도 임계값 0~1 (기본 0 = 필터 없음)", example = "0.5")
            @RequestParam(value = "threshold", defaultValue = "0.0") double threshold,

            @Parameter(description = "출발지 위도 (경도와 함께 지정 시 공간 필터)", example = "37.498")
            @RequestParam(value = "originLat", required = false) Double originLat,
            @Parameter(description = "출발지 경도", example = "127.0276")
            @RequestParam(value = "originLng", required = false) Double originLng,
            @Parameter(description = "도착지 위도 (경도와 함께 지정 시 공간 필터)", example = "37.557")
            @RequestParam(value = "destLat", required = false) Double destLat,
            @Parameter(description = "도착지 경도", example = "126.924")
            @RequestParam(value = "destLng", required = false) Double destLng,

            @Parameter(description = "이동수단 필터 (OR, 콤마로 여러 개). DDAREUNGI|PERSONAL_EBIKE|PERSONAL_KICKBOARD")
            @RequestParam(value = "mobility", required = false) List<String> mobility
    ) {
        if (routeHistoryPort == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("VECTOR_STORE_UNAVAILABLE", "벡터 DB 가 기동되지 않았습니다 (docker compose up -d qdrant)."));
        }
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("QUERY_REQUIRED", "쿼리 파라미터 'q' 가 비어 있습니다."));
        }

        int safeTopK = Math.max(1, Math.min(MAX_TOP_K, topK));
        List<MobilityType> mobilityFilter = MobilityTypes.parseLenient(mobility);
        Location origin = toLocation("출발지", originLat, originLng);
        Location destination = toLocation("도착지", destLat, destLng);

        RagSearchRequest request = RagSearchRequest.nearLocations(
                query, safeTopK, threshold, origin, destination, mobilityFilter);

        log.info("[RAG] similar-routes query=\"{}\" topK={} threshold={} origin={} dest={} mobility={}",
                query, safeTopK, request.similarityThreshold(), origin, destination, mobilityFilter);

        List<ScoredRouteHistoryEntry> scored = routeHistoryPort.search(request).block(SEARCH_TIMEOUT);
        List<SimilarRouteResponse> items = (scored == null ? List.<ScoredRouteHistoryEntry>of() : scored).stream()
                .map(SimilarRouteResponse::from)
                .toList();

        SimilarRoutesResponse.AppliedFilter filter = new SimilarRoutesResponse.AppliedFilter(
                origin == null ? null : PlaceResponse.from(origin),
                destination == null ? null : PlaceResponse.from(destination),
                mobilityFilter.stream().map(Enum::name).toList(),
                request.hasAnyPayloadFilter() || request.similarityThreshold() > 0.0
        );
        return ResponseEntity.ok(new SimilarRoutesResponse(
                query, safeTopK, request.similarityThreshold(), filter, items.size(), items));
    }

    /** 위도·경도가 모두 있어야 공간 필터로 쓴다. 한쪽만 오면 필터 없음. */
    private static Location toLocation(String name, Double lat, Double lng) {
        if (lat == null || lng == null) return null;
        return new Location(name, lat, lng);
    }
}
