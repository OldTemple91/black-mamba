package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;

import java.util.List;

/**
 * RAG 유사 검색 요청 파라미터 객체.
 * <p>
 * 검색 축이 3개(의미 + geohash + mobility) + 임계값으로 늘어나면서
 * 개별 메서드 오버로드가 폭증하기 때문에 Parameter Object 로 통합.
 *
 * <h3>검색 축 구성</h3>
 * <ul>
 *   <li><b>의미 유사도(벡터):</b> {@code query} 를 bge-m3 로 임베딩해 코사인 유사도</li>
 *   <li><b>공간 필터(payload):</b> {@code originGeohash}, {@code destinationGeohash}
 *       — 또는 {@code origin}/{@code destination} 좌표 (어댑터가 geohash 변환)</li>
 *   <li><b>이동수단 필터(payload):</b> {@code mobilityFilter}</li>
 *   <li><b>품질 임계값:</b> {@code similarityThreshold} 미만은 제외</li>
 * </ul>
 *
 * <h3>공간 필터의 두 입력 경로</h3>
 * <ul>
 *   <li><b>geohash 문자열</b> — API 가 geohash 를 직접 받는 경우 ({@link #byGeohash})</li>
 *   <li><b>Location 좌표</b> — application 레이어가 좌표만 가진 경우 ({@link #nearLocations}).
 *       geohash 변환은 infra 어댑터의 책임 (application 은 geohash 라이브러리를 모름)</li>
 * </ul>
 * 같은 축에 둘 다 주어지면 명시적 geohash 문자열이 우선한다.
 *
 * <h3>불변 원칙</h3>
 * record + compact constructor 로 null/음수 입력 방어.
 * null 컬렉션 → 빈 리스트, threshold 음수 → 0으로 클램프.
 *
 * @param query               자연어 쿼리 (필수, blank 불가)
 * @param topK                반환 개수 (1~50 구간, 그 외 클램프)
 * @param similarityThreshold 0.0~1.0 (0.0 = 필터 없음)
 * @param originGeohash       출발지 geohash7 필터 (null/빈 문자열 = 필터 없음)
 * @param destinationGeohash  도착지 geohash7 필터
 * @param origin              출발지 좌표 (geohash 미지정 시 어댑터가 변환, null 허용)
 * @param destination         도착지 좌표
 * @param mobilityFilter      포함되어야 할 이동수단 (OR 조건, 빈 리스트 = 필터 없음)
 */
public record RagSearchRequest(
        String query,
        int topK,
        double similarityThreshold,
        String originGeohash,
        String destinationGeohash,
        Location origin,
        Location destination,
        List<MobilityType> mobilityFilter
) {
    public RagSearchRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 는 필수입니다");
        }
        if (topK < 1) topK = 1;
        if (topK > 50) topK = 50;
        if (similarityThreshold < 0.0) similarityThreshold = 0.0;
        if (similarityThreshold > 1.0) similarityThreshold = 1.0;
        mobilityFilter = mobilityFilter == null ? List.of() : List.copyOf(mobilityFilter);
    }

    /** 기본값 빌더: threshold 0, 필터 없음. */
    public static RagSearchRequest of(String query, int topK) {
        return new RagSearchRequest(query, topK, 0.0, null, null, null, null, List.of());
    }

    /** geohash 문자열 기반 공간 필터 (API 경로 — 사용자가 geohash 를 직접 지정). */
    public static RagSearchRequest byGeohash(String query, int topK, double similarityThreshold,
                                             String originGeohash, String destinationGeohash,
                                             List<MobilityType> mobilityFilter) {
        return new RagSearchRequest(query, topK, similarityThreshold,
                originGeohash, destinationGeohash, null, null, mobilityFilter);
    }

    /** 좌표 기반 공간 필터 (application 경로 — geohash 변환은 infra 어댑터 책임). */
    public static RagSearchRequest nearLocations(String query, int topK, double similarityThreshold,
                                                 Location origin, Location destination,
                                                 List<MobilityType> mobilityFilter) {
        return new RagSearchRequest(query, topK, similarityThreshold,
                null, null, origin, destination, mobilityFilter);
    }

    public boolean hasOriginGeohash() {
        return originGeohash != null && !originGeohash.isBlank();
    }

    public boolean hasDestinationGeohash() {
        return destinationGeohash != null && !destinationGeohash.isBlank();
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
        return hasOriginGeohash() || hasDestinationGeohash()
                || hasOriginLocation() || hasDestinationLocation()
                || hasMobilityFilter();
    }
}
