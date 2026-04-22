package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Route;

import java.util.List;

/**
 * 경로 이력(벡터 DB) 에 대한 application 레이어 Port (Hexagonal Architecture).
 * <p>
 * <b>설계 원칙:</b>
 * <ul>
 *   <li>application/domain 은 벡터 DB/geohash 라이브러리 존재를 모름</li>
 *   <li>Entry 로의 변환 + geohash 계산 + 임베딩은 모두 adapter(infra) 내부에서 처리</li>
 *   <li>application 은 "Route 저장 요청" 만, 결과(find)만 Entry 로 받아 그대로 API 응답으로 전달</li>
 * </ul>
 * <p>
 * 구현체는 infra 에 있다:
 * <ul>
 *   <li>{@code QdrantRouteHistoryAdapter} — Spring AI VectorStore 래퍼 (운영)</li>
 *   <li>{@code NoopRouteHistoryAdapter} — Qdrant 미기동 시 폴백 (local/test profile)</li>
 * </ul>
 */
public interface RouteHistoryPort {

    /**
     * 경로를 벡터 DB 에 upsert.
     * <p>
     * 구현체 내부에서 Route → {@link RouteHistoryEntry} 변환, 텍스트 서술 생성,
     * geohash 계산, 임베딩(bge-m3), Qdrant upsert 수행.
     * <p>
     * 실패해도 본 요청은 성공해야 하므로 구현체는 비동기/로그 형태로 예외를 삼킬 것을 권장.
     */
    void save(Route route, Location origin, Location destination, String preference);

    /**
     * 자연어 쿼리와 유사한 과거 경로를 top-K 개 조회 (의미 유사도).
     *
     * @param query 자연어 설명 (예: "강남에서 홍대까지 빠르게")
     * @param topK  반환 개수 (보통 3~10)
     * @return 유사도 내림차순. 각 항목에 0~1 사이의 similarityScore 포함.
     */
    List<ScoredRouteHistoryEntry> findSimilar(String query, int topK);

    /**
     * geohash 필터를 적용한 유사 검색.
     * "출발/도착 격자가 같은 과거 경로 중 의미적으로 유사한 것" 을 찾을 때 사용.
     *
     * @param query              자연어 쿼리
     * @param topK               반환 개수
     * @param originGeohash      출발지 geohash7 (null → 필터 안 함)
     * @param destinationGeohash 도착지 geohash7 (null → 필터 안 함)
     * @return 유사도 내림차순. 각 항목에 similarityScore 포함.
     */
    List<ScoredRouteHistoryEntry> findSimilarInGeohash(String query, int topK,
                                                        String originGeohash,
                                                        String destinationGeohash);
}
