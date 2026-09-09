package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;

import java.util.List;

/**
 * RAG 유사 검색 요청 파라미터 객체.
 * <p>
 * 검색 축이 3개(의미 + 공간 + 이동수단) + 임계값으로 늘어나면서
 * 개별 메서드 오버로드가 폭증하기 때문에 Parameter Object 로 통합.
 *
 * <h3>검색 축 구성</h3>
 * <ul>
 *   <li><b>의미 유사도(벡터):</b> {@code query} 를 임베딩해 코사인 유사도</li>
 *   <li><b>공간 필터(payload):</b> {@code origin}/{@code destination} 좌표 —
 *       격자 인코딩(geohash 등)은 저장소 어댑터의 관심사라 여기엔 좌표만 있다</li>
 *   <li><b>이동수단 필터(payload):</b> {@code mobilityFilter}</li>
 *   <li><b>품질 임계값:</b> {@code similarityThreshold} 미만은 제외</li>
 * </ul>
 *
 * <h3>불변 원칙</h3>
 * record + compact constructor 로 null/음수 입력 방어.
 * null 컬렉션 → 빈 리스트, threshold 는 0~1 로 클램프, topK 는 1~50 로 클램프.
 *
 * @param query               자연어 쿼리 (필수, blank 불가)
 * @param topK                반환 개수 (1~50 구간, 그 외 클램프)
 * @param similarityThreshold 0.0~1.0 (0.0 = 필터 없음)
 * @param origin              출발지 좌표 (null = 공간 필터 없음)
 * @param destination         도착지 좌표 (null = 공간 필터 없음)
 * @param mobilityFilter      포함되어야 할 이동수단 (OR 조건, 빈 리스트 = 필터 없음)
 */
public record RagSearchRequest(
        String query,
        int topK,
        double similarityThreshold,
        Location origin,
        Location destination,
        List<MobilityType> mobilityFilter
) {
    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 50;

    public RagSearchRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 는 필수입니다");
        }
        topK = Math.max(MIN_TOP_K, Math.min(MAX_TOP_K, topK));
        similarityThreshold = Math.max(0.0, Math.min(1.0, similarityThreshold));
        mobilityFilter = mobilityFilter == null ? List.of() : List.copyOf(mobilityFilter);
    }

    /** 기본값: threshold 0, 필터 없음. */
    public static RagSearchRequest of(String query, int topK) {
        return new RagSearchRequest(query, topK, 0.0, null, null, List.of());
    }

    public static RagSearchRequest nearLocations(String query, int topK, double similarityThreshold,
                                                 Location origin, Location destination,
                                                 List<MobilityType> mobilityFilter) {
        return new RagSearchRequest(query, topK, similarityThreshold, origin, destination, mobilityFilter);
    }

    public boolean hasOriginLocation() {
        return origin != null;
    }

    public boolean hasDestinationLocation() {
        return destination != null;
    }

    public boolean hasMobilityFilter() {
        return !mobilityFilter.isEmpty();
    }

    public boolean hasAnyPayloadFilter() {
        return hasOriginLocation() || hasDestinationLocation() || hasMobilityFilter();
    }
}
