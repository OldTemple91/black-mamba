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
 *   <li>검색은 {@link RagSearchRequest} 파라미터 객체로 통합 — 축이 늘어나도 시그니처 불변</li>
 * </ul>
 * <p>
 * 구현체:
 * <ul>
 *   <li>{@code QdrantRouteHistoryAdapter} — Spring AI VectorStore 래퍼 (운영)</li>
 *   <li>Qdrant 빈 부재 시엔 주입 자체가 없음 — {@code ObjectProvider} 로 Optional 처리</li>
 * </ul>
 */
public interface RouteHistoryPort {

    /**
     * 경로를 벡터 DB 에 upsert. 실패해도 본 요청은 성공해야 함.
     */
    void save(Route route, Location origin, Location destination, String preference);

    /**
     * 유사 경로 이력 조회 (의미 + payload 필터 하이브리드).
     *
     * @return 유사도 내림차순. {@link RagSearchRequest#similarityThreshold} 미만은 제외.
     */
    List<ScoredRouteHistoryEntry> search(RagSearchRequest request);
}
