package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.history.RouteHistoryRecorder;
import com.blackmamba.navigation.application.route.strategy.RouteSearchStrategy;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 경로 탐색 유스케이스.
 * <pre>
 *   전략 실행 (searchMode 별) → 후처리 파이프라인 (접근성·날씨·탄소·자가용 비교) → 이력 기록 (fire-and-forget)
 * </pre>
 * 전략의 의존 그래프는 {@link RouteSearchStrategyRegistry}, 후처리 단계 구성은
 * {@link RouteEnrichmentPipeline} 이 안다 — 이 클래스는 흐름만 조립한다.
 */
@Service
public class RouteOptimizationService {

    private final RouteSearchStrategyRegistry strategyRegistry;
    private final RouteEnrichmentPipeline enrichmentPipeline;
    private final RouteHistoryRecorder routeHistoryRecorder;

    public RouteOptimizationService(RouteSearchStrategyRegistry strategyRegistry,
                                    RouteEnrichmentPipeline enrichmentPipeline,
                                    RouteHistoryRecorder routeHistoryRecorder) {
        this.strategyRegistry = strategyRegistry;
        this.enrichmentPipeline = enrichmentPipeline;
        this.routeHistoryRecorder = routeHistoryRecorder;
    }

    @Observed(name = "navigation.route.search",
            contextualName = "경로 탐색",
            lowCardinalityKeyValues = {"component", "RouteOptimizationService"})
    public Mono<List<Route>> findRoutes(RouteSearchQuery query) {
        RouteSearchStrategy strategy = strategyRegistry.strategyFor(query.searchMode());
        Location origin = query.origin();
        Location destination = query.destination();
        RecommendationPreference preference = query.preference();

        return strategy.search(origin, destination, query.mobilityTypes(), preference)
                .map(routes -> enrichmentPipeline.enrich(routes, origin, destination, preference, query.accessibility(), query.weather()))
                // RAG Phase 2: 추천 경로를 벡터 DB 에 비동기 저장 (fire-and-forget, 본 요청에 영향 없음)
                .doOnNext(routes -> routeHistoryRecorder.recordAsync(routes, origin, destination, preference));
    }
}
