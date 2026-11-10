package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * 경로 이력(벡터 DB) 에 대한 application 레이어 Port (Hexagonal Architecture).
 * <p>
 * <b>설계 원칙:</b>
 * <ul>
 *   <li>application/domain 은 벡터 DB/공간 인덱스 라이브러리 존재를 모름 — 좌표만 넘긴다</li>
 *   <li>Entry 로의 변환 + 격자 인코딩 + 임베딩은 모두 adapter(infra) 내부에서 처리</li>
 *   <li>검색은 {@link RagSearchRequest} 파라미터 객체로 통합 — 축이 늘어나도 시그니처 불변</li>
 *   <li>다른 외부 포트({@code TransitRoutePort} 등)와 같이 Reactor 타입으로 계약한다.
 *       블로킹 SDK 를 어느 스케줄러에 격리할지는 어댑터가 정한다 — 호출자가 매번
 *       {@code subscribeOn} 을 붙이지 않는다</li>
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
     * 경로를 벡터 DB 에 upsert (현재 시각 기준 맥락 태그 자동 부여).
     * 실패해도 본 요청은 성공해야 한다 — 구현체는 오류를 로그로 삼키고 정상 완료한다.
     */
    default Mono<Void> save(Route route, Location origin, Location destination, RecommendationPreference preference) {
        return save(route, origin, destination, preference, null);
    }

    /**
     * 지정된 시각을 맥락 태그 기준으로 사용해 upsert.
     * 시드 데이터를 과거 다양한 시간대로 주입할 때 사용.
     *
     * @param timestamp null 이면 현재 시각 사용
     */
    Mono<Void> save(Route route, Location origin, Location destination, RecommendationPreference preference, Instant timestamp);

    /**
     * 유사 경로 이력 조회 (의미 + payload 필터 하이브리드).
     *
     * @return 유사도 내림차순. {@link RagSearchRequest#similarityThreshold} 미만은 제외.
     *         실패 시 빈 리스트 (empty Mono 가 아니다 — zip 안전).
     */
    Mono<List<ScoredRouteHistoryEntry>> search(RagSearchRequest request);

    /**
     * 이 경로를 이력에 저장한다면 쓰일 서술 문장.
     * 유사 이력을 찾을 때 검색어로 쓴다 — 저장된 문서와 같은 형식의 문장이어야 벡터 유사도가 의미를 가진다.
     */
    String describe(Route route, Location origin, Location destination, RecommendationPreference preference);
}
