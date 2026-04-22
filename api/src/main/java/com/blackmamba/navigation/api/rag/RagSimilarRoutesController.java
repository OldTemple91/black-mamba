package com.blackmamba.navigation.api.rag;

import com.blackmamba.navigation.application.route.port.RouteHistoryPort;
import com.blackmamba.navigation.application.route.port.ScoredRouteHistoryEntry;
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

import java.util.List;
import java.util.Map;

/**
 * RAG Phase 2 — 경로 이력 유사 검색 엔드포인트.
 * <p>
 * 자연어 쿼리를 bge-m3 로 임베딩한 뒤 Qdrant 에 저장된 과거 경로 이력과
 * 코사인 유사도를 계산해 top-K 를 반환한다.
 *
 * <h3>의미 검색 (semantic search) 의 가치</h3>
 * 단순 키워드 매칭이 아니라 <b>의미 벡터 거리</b> 로 검색한다.
 * <ul>
 *   <li>"빠른 지하철 경로" → TIME_PRIORITY + 지하철 성향 이력 상위</li>
 *   <li>"따릉이로 가는 길" → mobility=[DDAREUNGI] 포함 이력 상위</li>
 *   <li>"환승 적은 안정적" → RELIABILITY + 직행 이력 상위</li>
 * </ul>
 *
 * <h3>geohash payload 필터</h3>
 * 벡터 유사도(의미)에 더해 "출발/도착 격자" 를 EQ 필터로 넣어
 * <b>하이브리드 검색</b> (의미 + 공간) 이 가능하다.
 */
@Tag(name = "RAG 유사 경로", description = "벡터 DB 기반 의미 유사 경로 검색")
@RestController
@RequestMapping("/api/rag")
public class RagSimilarRoutesController {

    private static final Logger log = LoggerFactory.getLogger(RagSimilarRoutesController.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 50;

    private final RouteHistoryPort routeHistoryPort; // null 가능 (Qdrant 빈 부재 시)

    public RagSimilarRoutesController(ObjectProvider<RouteHistoryPort> portProvider) {
        this.routeHistoryPort = portProvider.getIfAvailable();
        if (this.routeHistoryPort == null) {
            log.warn("[RAG] RouteHistoryPort 빈 없음 — /api/rag/similar-routes 가 503 응답합니다");
        }
    }

    @Operation(
            summary = "유사 경로 의미 검색",
            description = """
                    자연어 쿼리와 **의미적으로 유사한** 과거 경로 이력을 top-K 개 반환합니다.

                    **예시:**
                    - `q=빠른 지하철 경로` → TIME_PRIORITY + SUBWAY 성향 상위
                    - `q=따릉이로 출퇴근` → mobility=[DDAREUNGI] 포함 상위
                    - `q=환승 없는 직행` → RELIABILITY + 직행 성향 상위

                    **geohash 필터 (선택):**
                    - `originGeohash=wydm6d6` → 해당 150m 격자에서 출발한 경로만
                    - `destinationGeohash=wydm8jp` → 해당 격자로 도착한 경로만
                    - 두 격자 모두 주면 AND 조건
                    """
    )
    @GetMapping("/similar-routes")
    public ResponseEntity<?> findSimilarRoutes(
            @Parameter(description = "자연어 검색 쿼리", example = "빠른 지하철 경로", required = true)
            @RequestParam("q") String query,

            @Parameter(description = "반환 개수 (1~50, 기본 5)", example = "5")
            @RequestParam(value = "topK", defaultValue = "5") int topK,

            @Parameter(description = "출발지 geohash7 필터 (선택, 예: wydm6d6)")
            @RequestParam(value = "originGeohash", required = false) String originGeohash,

            @Parameter(description = "도착지 geohash7 필터 (선택, 예: wydm8jp)")
            @RequestParam(value = "destinationGeohash", required = false) String destinationGeohash
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
        log.info("[RAG] similar-routes query=\"{}\" topK={} originGeohash={} destGeohash={}",
                query, safeTopK, originGeohash, destinationGeohash);

        boolean hasFilter = (originGeohash != null && !originGeohash.isBlank())
                || (destinationGeohash != null && !destinationGeohash.isBlank());

        List<ScoredRouteHistoryEntry> scoredEntries = hasFilter
                ? routeHistoryPort.findSimilarInGeohash(query, safeTopK, originGeohash, destinationGeohash)
                : routeHistoryPort.findSimilar(query, safeTopK);

        List<SimilarRouteResponse> items = scoredEntries.stream()
                .map(SimilarRouteResponse::from)
                .toList();

        return ResponseEntity.ok(Map.of(
                "query", query,
                "topK", safeTopK,
                "filter", Map.of(
                        "originGeohash", originGeohash == null ? "" : originGeohash,
                        "destinationGeohash", destinationGeohash == null ? "" : destinationGeohash,
                        "applied", hasFilter
                ),
                "hitCount", items.size(),
                "results", items
        ));
    }
}
