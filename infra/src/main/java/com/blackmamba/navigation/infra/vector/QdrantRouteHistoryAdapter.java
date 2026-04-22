package com.blackmamba.navigation.infra.vector;

import com.blackmamba.navigation.application.route.port.RagSearchRequest;
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
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Qdrant 기반 {@link RouteHistoryPort} 구현 (RAG Phase 2).
 *
 * <h3>Payload 스키마</h3>
 * <pre>
 * {
 *   "routeId": "uuid",
 *   "originGeohash": "wydm6rk",
 *   "destinationGeohash": "wydm9tq",
 *   "mobilityTypes": "DDAREUNGI,PERSONAL_EBIKE",  // 기록용 (사람이 읽기 쉬움)
 *   "has_DDAREUNGI": true,                        // 필터용 (EQ 조건)
 *   "has_PERSONAL_EBIKE": true,
 *   "routeType": "MOBILITY_FIRST_TRANSIT",
 *   "totalMinutes": 36,
 *   ...
 * }
 * </pre>
 *
 * <h3>검색 축 3개 + 임계값</h3>
 * <ol>
 *   <li>의미 유사도: bge-m3 임베딩 → 코사인 유사도 (벡터)</li>
 *   <li>공간 필터: originGeohash / destinationGeohash (payload EQ, AND)</li>
 *   <li>이동수단 필터: has_&lt;MobilityType&gt; (payload EQ, OR)</li>
 *   <li>품질 임계값: similarityThreshold 미만 제외 (Spring AI 자체 지원)</li>
 * </ol>
 *
 * <h3>장애 대응</h3>
 * save/search 공통: 예외 삼키고 warn 로그만. 본 요청 응답 흐름 보호.
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
    static final String META_MOBILITY_TYPES = "mobilityTypes";  // comma-separated (기록용)
    static final String META_ROUTE_TYPE = "routeType";
    static final String META_TOTAL_MINUTES = "totalMinutes";
    static final String META_TOTAL_COST = "totalCostWon";
    static final String META_PREFERENCE = "preference";
    static final String META_CREATED_AT = "createdAt";          // epoch seconds
    /** 이동수단 boolean 플래그 접두사 — 필터용. ex) has_DDAREUNGI=true */
    static final String META_MOBILITY_FLAG_PREFIX = "has_";

    private final VectorStore vectorStore;

    public QdrantRouteHistoryAdapter(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // ─── 쓰기 ─────────────────────────────────────

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
            // 각 MobilityType 마다 "Y" 문자열 플래그.
            // "true" 같이 boolean 파싱 가능한 값은 Spring AI 가 자동으로 bool 변환해
            // Qdrant 에 bool 타입으로 저장 → string EQ 필터가 안 먹힘. "Y" 는 절대 bool 로 변환 안 됨.
            for (MobilityType m : mobilityTypes) {
                metadata.put(META_MOBILITY_FLAG_PREFIX + m.name(), "Y");
            }
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
            log.warn("[RAG] Qdrant 저장 실패 — 본 요청에는 영향 없음. routeId={}, err={}",
                    route.routeId(), e.getMessage());
        }
    }

    // ─── 읽기 ─────────────────────────────────────

    @Override
    public List<ScoredRouteHistoryEntry> search(RagSearchRequest request) {
        if (request == null) return List.of();
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(request.query())
                    .topK(request.topK());
            if (request.similarityThreshold() > 0.0) {
                builder.similarityThreshold(request.similarityThreshold());
            }
            Filter.Expression filter = buildFilter(request);
            if (filter != null) builder.filterExpression(filter);

            List<Document> results = vectorStore.similaritySearch(builder.build());
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
            log.warn("[RAG] Qdrant 검색 실패. query=\"{}\", err={}",
                    request.query(), e.getMessage());
            return List.of();
        }
    }

    /**
     * RagSearchRequest → Qdrant payload filter expression.
     * <p>
     * geohash AND (mobilityFilter 중 OR). 아무 필터도 없으면 null.
     */
    private Filter.Expression buildFilter(RagSearchRequest request) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<Op> parts = new ArrayList<>();

        if (request.hasOriginGeohash()) {
            parts.add(b.eq(META_ORIGIN_GEOHASH, request.originGeohash()));
        }
        if (request.hasDestinationGeohash()) {
            parts.add(b.eq(META_DEST_GEOHASH, request.destinationGeohash()));
        }
        if (request.hasMobilityFilter()) {
            // OR of has_XXX flags
            Op orOfMobility = null;
            for (MobilityType m : request.mobilityFilter()) {
                Op flag = b.eq(META_MOBILITY_FLAG_PREFIX + m.name(), "Y");
                orOfMobility = (orOfMobility == null) ? flag : b.or(orOfMobility, flag);
            }
            if (orOfMobility != null) parts.add(orOfMobility);
        }

        if (parts.isEmpty()) return null;
        Op combined = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            combined = b.and(combined, parts.get(i));
        }
        return combined.build();
    }

    // ─── Document → Entry ─────────────────────────

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

    /**
     * Spring AI Document 에서 유사도 점수 추출.
     * 우선순위: Document.getScore() → metadata "distance" → 0.0.
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
            return 1.0 - n.doubleValue();
        }
        return 0.0;
    }

    // ─── helpers ─────────────────────────────────

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
}
