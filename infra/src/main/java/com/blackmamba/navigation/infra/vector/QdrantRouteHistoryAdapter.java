package com.blackmamba.navigation.infra.vector;

import com.blackmamba.navigation.application.route.port.RouteHistoryPort;
import com.blackmamba.navigation.application.route.port.ScoredRouteHistoryEntry;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteHistoryEntry;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.infra.common.GeohashKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Qdrant 기반 {@link RouteHistoryPort} 구현 (RAG Phase 2).
 * <p>
 * Spring AI {@link VectorStore} 를 래핑해:
 * <ol>
 *   <li><b>save</b>: Route → 자연어 서술 → bge-m3 임베딩(1024차원) → Qdrant upsert</li>
 *   <li><b>findSimilar</b>: 쿼리 → 임베딩 → 코사인 유사도 top-K</li>
 *   <li><b>findSimilarInGeohash</b>: payload filter 조합 (격자 단위 유사 검색)</li>
 * </ol>
 *
 * <h3>Payload 스키마</h3>
 * <pre>
 * {
 *   "routeId": "uuid",
 *   "originGeohash": "wydm6rk",
 *   "destinationGeohash": "wydm9tq",
 *   "mobilityTypes": "DDAREUNGI,PERSONAL_EBIKE",
 *   "routeType": "MOBILITY_FIRST_TRANSIT",
 *   "totalMinutes": 36,
 *   "totalCostWon": 1650,
 *   "preference": "RELIABILITY",
 *   "createdAt": 1745193600
 * }
 * </pre>
 *
 * <h3>장애 대응</h3>
 * save() 는 어떤 예외도 위로 던지지 않는다. 임베딩/Qdrant 장애가 경로 탐색 본 요청을
 * 막으면 안 되기 때문. 대신 경고 로그 + 메트릭(추후 M-1).
 */
@Component
public class QdrantRouteHistoryAdapter implements RouteHistoryPort {

    private static final Logger log = LoggerFactory.getLogger(QdrantRouteHistoryAdapter.class);

    // Payload 키 (Qdrant metadata) — 변경 시 검색 필터도 같이 바꿔야 함
    static final String META_ROUTE_ID = "routeId";
    static final String META_ORIGIN_GEOHASH = "originGeohash";
    static final String META_DEST_GEOHASH = "destinationGeohash";
    static final String META_ORIGIN_NAME = "originName";
    static final String META_DEST_NAME = "destinationName";
    static final String META_ORIGIN_LAT = "originLat";
    static final String META_ORIGIN_LNG = "originLng";
    static final String META_DEST_LAT = "destinationLat";
    static final String META_DEST_LNG = "destinationLng";
    static final String META_MOBILITY_TYPES = "mobilityTypes";  // comma-separated
    static final String META_ROUTE_TYPE = "routeType";
    static final String META_TOTAL_MINUTES = "totalMinutes";
    static final String META_TOTAL_COST = "totalCostWon";
    static final String META_PREFERENCE = "preference";
    static final String META_CREATED_AT = "createdAt";         // epoch seconds

    private final VectorStore vectorStore;

    public QdrantRouteHistoryAdapter(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void save(Route route, Location origin, Location destination, String preference) {
        if (route == null || origin == null || destination == null) {
            log.debug("[RAG] save 생략 — route/origin/destination null");
            return;
        }

        try {
            String description = RouteHistoryDescriber.describe(route, origin, destination, preference);
            String originGeohash = GeohashKeyGenerator.of(origin);
            String destGeohash = GeohashKeyGenerator.of(destination);
            List<MobilityType> mobilityTypes = RouteHistoryDescriber.extractMobilityTypes(route.legs());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put(META_ROUTE_ID, route.routeId());
            metadata.put(META_ORIGIN_GEOHASH, originGeohash);
            metadata.put(META_DEST_GEOHASH, destGeohash);
            metadata.put(META_ORIGIN_NAME, safeName(origin));
            metadata.put(META_DEST_NAME, safeName(destination));
            metadata.put(META_ORIGIN_LAT, origin.lat());
            metadata.put(META_ORIGIN_LNG, origin.lng());
            metadata.put(META_DEST_LAT, destination.lat());
            metadata.put(META_DEST_LNG, destination.lng());
            metadata.put(META_MOBILITY_TYPES, mobilityTypes.stream()
                    .map(Enum::name)
                    .collect(Collectors.joining(",")));
            metadata.put(META_ROUTE_TYPE, route.type() == null ? "" : route.type().name());
            metadata.put(META_TOTAL_MINUTES, route.totalMinutes());
            metadata.put(META_TOTAL_COST, route.totalCostWon());
            metadata.put(META_PREFERENCE, preference == null ? "RELIABILITY" : preference);
            metadata.put(META_CREATED_AT, Instant.now().getEpochSecond());

            Document document = new Document(route.routeId(), description, metadata);
            vectorStore.add(List.of(document));
            log.info("[RAG] 경로 이력 저장: routeId={} desc=\"{}\" ({}→{})",
                    route.routeId(), description, originGeohash, destGeohash);
        } catch (Exception e) {
            // 본 요청을 막지 않는다
            log.warn("[RAG] Qdrant 저장 실패 — 본 요청에는 영향 없음. routeId={}, err={}",
                    route.routeId(), e.getMessage());
        }
    }

    @Override
    public List<ScoredRouteHistoryEntry> findSimilar(String query, int topK) {
        return search(query, topK, null);
    }

    @Override
    public List<ScoredRouteHistoryEntry> findSimilarInGeohash(String query, int topK,
                                                               String originGeohash,
                                                               String destinationGeohash) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        boolean hasOrigin = originGeohash != null && !originGeohash.isBlank();
        boolean hasDest = destinationGeohash != null && !destinationGeohash.isBlank();

        Filter.Expression expr = null;
        if (hasOrigin && hasDest) {
            expr = b.and(
                    b.eq(META_ORIGIN_GEOHASH, originGeohash),
                    b.eq(META_DEST_GEOHASH, destinationGeohash)
            ).build();
        } else if (hasOrigin) {
            expr = b.eq(META_ORIGIN_GEOHASH, originGeohash).build();
        } else if (hasDest) {
            expr = b.eq(META_DEST_GEOHASH, destinationGeohash).build();
        }
        return search(query, topK, expr);
    }

    // ─── 내부 검색 ─────────────────────────────

    private List<ScoredRouteHistoryEntry> search(String query, int topK, Filter.Expression filter) {
        if (query == null || query.isBlank()) return List.of();
        try {
            SearchRequest.Builder requestBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(topK);
            if (filter != null) requestBuilder.filterExpression(filter);

            List<Document> results = vectorStore.similaritySearch(requestBuilder.build());
            if (results == null || results.isEmpty()) return List.of();

            List<ScoredRouteHistoryEntry> scored = new ArrayList<>(results.size());
            for (Document doc : results) {
                try {
                    RouteHistoryEntry entry = toEntry(doc);
                    double score = extractScore(doc);
                    scored.add(new ScoredRouteHistoryEntry(entry, score));
                } catch (Exception e) {
                    log.warn("[RAG] 검색 결과 파싱 실패 — 무시. id={}, err={}", doc.getId(), e.getMessage());
                }
            }
            return scored;
        } catch (Exception e) {
            log.warn("[RAG] Qdrant 검색 실패. query=\"{}\", err={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * Spring AI Document 에서 유사도 점수 추출.
     * <p>
     * 우선순위: Document.getScore() → metadata "distance" → 0.0.
     * Qdrant 는 distance 가 아니라 score 를 반환 (코사인 유사도는 높을수록 유사).
     */
    private static double extractScore(Document doc) {
        try {
            Double s = doc.getScore();
            if (s != null) return s;
        } catch (Throwable ignore) {
            // Spring AI 버전차: getScore() 없으면 metadata 폴백
        }
        Object dist = doc.getMetadata().get("distance");
        if (dist instanceof Number n) {
            // distance 기반이라면 (1 - distance) 로 유사도 환산 (cosine distance 전제)
            return 1.0 - n.doubleValue();
        }
        return 0.0;
    }

    private RouteHistoryEntry toEntry(Document doc) {
        Map<String, Object> md = doc.getMetadata();
        String routeId = asString(md.get(META_ROUTE_ID), doc.getId());
        Location origin = new Location(
                asString(md.get(META_ORIGIN_NAME), ""),
                asDouble(md.get(META_ORIGIN_LAT)),
                asDouble(md.get(META_ORIGIN_LNG)));
        Location destination = new Location(
                asString(md.get(META_DEST_NAME), ""),
                asDouble(md.get(META_DEST_LAT)),
                asDouble(md.get(META_DEST_LNG)));
        List<MobilityType> mobilityTypes = parseMobilityTypes(asString(md.get(META_MOBILITY_TYPES), ""));
        RouteType routeType = parseRouteType(asString(md.get(META_ROUTE_TYPE), ""));
        int minutes = asInt(md.get(META_TOTAL_MINUTES));
        int cost = asInt(md.get(META_TOTAL_COST));
        String preference = asString(md.get(META_PREFERENCE), "RELIABILITY");
        long createdEpoch = asLong(md.get(META_CREATED_AT));

        // doc.getText() 는 Spring AI 1.0 부터 지원 (이전엔 getContent)
        String description = safeDocumentText(doc);

        return new RouteHistoryEntry(
                routeId,
                description,
                origin,
                destination,
                asString(md.get(META_ORIGIN_GEOHASH), ""),
                asString(md.get(META_DEST_GEOHASH), ""),
                mobilityTypes,
                routeType,
                minutes,
                cost,
                preference,
                createdEpoch > 0 ? Instant.ofEpochSecond(createdEpoch) : Instant.now());
    }

    // ─── helpers ─────────────────────────────

    private static String safeName(Location loc) {
        return loc.name() == null ? "" : loc.name();
    }

    private static String safeDocumentText(Document doc) {
        try {
            return doc.getText();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String asString(Object v, String defaultValue) {
        return v == null ? defaultValue : v.toString();
    }

    private static double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0.0;
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0.0; }
    }

    private static int asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return 0;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return 0L;
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0L; }
    }

    private static List<MobilityType> parseMobilityTypes(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<MobilityType> list = new ArrayList<>();
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try { list.add(MobilityType.valueOf(t)); } catch (Exception ignore) {}
        }
        return list;
    }

    private static RouteType parseRouteType(String name) {
        if (name == null || name.isBlank()) return null;
        try { return RouteType.valueOf(name); } catch (Exception e) { return null; }
    }

    // MobilityType set import helper (used only for type checker not to warn)
    @SuppressWarnings("unused")
    private static final Set<?> _UNUSED = Set.of();
}
