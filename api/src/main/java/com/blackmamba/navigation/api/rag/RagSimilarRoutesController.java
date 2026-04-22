package com.blackmamba.navigation.api.rag;

import com.blackmamba.navigation.application.route.port.RagSearchRequest;
import com.blackmamba.navigation.application.route.port.RouteHistoryPort;
import com.blackmamba.navigation.application.route.port.ScoredRouteHistoryEntry;
import com.blackmamba.navigation.domain.route.MobilityType;
import io.micrometer.observation.annotation.Observed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG Phase 2 — 경로 이력 유사 검색 엔드포인트.
 *
 * <h3>3축 하이브리드 검색</h3>
 * <ul>
 *   <li>의미 유사도 (벡터): bge-m3 임베딩 + 코사인</li>
 *   <li>공간 필터 (payload): geohash7 격자 AND</li>
 *   <li>이동수단 필터 (payload): MobilityType OR</li>
 * </ul>
 * 품질 임계값({@code threshold}) 으로 무관한 쿼리는 빈 응답을 정직하게 준다.
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
                    - `originGeohash` / `destinationGeohash`: 150m 격자 필터 (AND)
                    - `mobility`: 이동수단 필터 — `DDAREUNGI`, `PERSONAL_EBIKE`, `PERSONAL_KICKBOARD` (OR, 콤마로 여러 개)
                    - `threshold`: 유사도 임계값 (0.0~1.0, 기본 0)

                    **예시:**
                    - `q=출퇴근 빠른 경로&mobility=DDAREUNGI`
                      → 따릉이 포함 + '출퇴근 빠른' 의미
                    - `q=빠른 경로&originGeohash=wydm6d6&threshold=0.5`
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

            @Parameter(description = "출발지 geohash7 필터 (선택, 예: wydm6d6)")
            @RequestParam(value = "originGeohash", required = false) String originGeohash,

            @Parameter(description = "도착지 geohash7 필터 (선택, 예: wydm8jp)")
            @RequestParam(value = "destinationGeohash", required = false) String destinationGeohash,

            @Parameter(description = "이동수단 필터 (OR, 콤마로 여러 개). DDAREUNGI|PERSONAL_EBIKE|PERSONAL_KICKBOARD")
            @RequestParam(value = "mobility", required = false) List<String> mobility
    ) {
        if (routeHistoryPort == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "code", "VECTOR_STORE_UNAVAILABLE",
                    "message", "벡터 DB 가 기동되지 않았습니다 (docker compose up -d qdrant)."
            ));
        }
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "QUERY_REQUIRED",
                    "message", "쿼리 파라미터 'q' 가 비어 있습니다."
            ));
        }

        int safeTopK = Math.max(1, Math.min(MAX_TOP_K, topK));
        List<MobilityType> mobilityFilter = parseMobility(mobility);

        RagSearchRequest request = new RagSearchRequest(
                query, safeTopK, threshold,
                originGeohash, destinationGeohash, mobilityFilter
        );

        log.info("[RAG] similar-routes query=\"{}\" topK={} threshold={} geohash=({},{}) mobility={}",
                query, safeTopK, request.similarityThreshold(),
                originGeohash, destinationGeohash, mobilityFilter);

        List<ScoredRouteHistoryEntry> scored = routeHistoryPort.search(request);
        List<SimilarRouteResponse> items = scored.stream()
                .map(SimilarRouteResponse::from)
                .toList();

        return ResponseEntity.ok(Map.of(
                "query", query,
                "topK", safeTopK,
                "threshold", request.similarityThreshold(),
                "filter", Map.of(
                        "originGeohash", originGeohash == null ? "" : originGeohash,
                        "destinationGeohash", destinationGeohash == null ? "" : destinationGeohash,
                        "mobility", mobilityFilter.stream().map(Enum::name).toList(),
                        "applied", request.hasAnyPayloadFilter() || request.similarityThreshold() > 0.0
                ),
                "hitCount", items.size(),
                "results", items
        ));
    }

    private static List<MobilityType> parseMobility(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<MobilityType> types = new ArrayList<>();
        for (String token : raw) {
            if (token == null) continue;
            for (String t : token.split(",")) {
                String trimmed = t.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    types.add(MobilityType.valueOf(trimmed));
                } catch (IllegalArgumentException ignored) {
                    log.debug("[RAG] 알 수 없는 mobility 값 무시: {}", trimmed);
                }
            }
        }
        return types;
    }
}
