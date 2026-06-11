package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.NarrativeGenerator;
import com.blackmamba.navigation.application.route.port.RagSearchRequest;
import com.blackmamba.navigation.application.route.port.RouteHistoryPort;
import com.blackmamba.navigation.application.route.port.ScoredRouteHistoryEntry;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteComparison;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * RAG Phase 4 — 경로의 carComparison.narrative 를 LLM 설명으로 업그레이드.
 *
 * <h3>파이프라인 (Retrieval → Augmented → Generation)</h3>
 * <ol>
 *   <li><b>R</b>: 현재 OD 의 geohash 로 Qdrant 에서 유사 이력 top 3 조회</li>
 *   <li><b>A</b>: "현재 경로 + 자가용 비교 원본 + 유사 이력" 을 LLM 프롬프트에 주입</li>
 *   <li><b>G</b>: 로컬 LLM(llama3.2:3b) 이 한국어 narrative 2~3문장 생성</li>
 * </ol>
 *
 * <h3>적용 범위 (A' 정책)</h3>
 * - {@link Route#recommended()} = true 인 경로 <b>1개에만</b> LLM 호출
 * - 경로 4~5개에 모두 호출하면 지연 누적 → 추천 1개로 제한
 *
 * <h3>장애 정책</h3>
 * - LLM 실패 → 원본 narrative 유지
 * - NarrativeGenerator 또는 RouteHistoryPort 빈 부재 → 전체 스킵 (경로 원본 그대로)
 * - 개별 LLM 호출에 타임아웃 걸어 본 응답 끊지 않음
 */
@Component
public class RouteNarrativeEnhancer {

    private static final Logger log = LoggerFactory.getLogger(RouteNarrativeEnhancer.class);

    private static final int SIMILAR_TOP_K = 3;
    private static final double SIMILAR_MIN_SCORE = 0.35; // 무관한 이력 배제
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(15);

    private final NarrativeGenerator narrativeGenerator; // 없을 수 있음
    private final RouteHistoryPort routeHistoryPort;      // 없을 수 있음
    private final DistributionSummary similarHitSummary;

    public RouteNarrativeEnhancer(ObjectProvider<NarrativeGenerator> generatorProvider,
                                  ObjectProvider<RouteHistoryPort> historyProvider,
                                  MeterRegistry meterRegistry) {
        this.narrativeGenerator = generatorProvider.getIfAvailable();
        this.routeHistoryPort = historyProvider.getIfAvailable();
        this.similarHitSummary = DistributionSummary.builder("navigation.rag.narrative.similar_hit")
                .description("narrative 생성 시 참고한 유사 이력 건수 분포")
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry);

        if (narrativeGenerator == null || routeHistoryPort == null) {
            log.info("[RAG4] 비활성화 — generator={}, historyPort={}",
                    narrativeGenerator != null, routeHistoryPort != null);
        } else {
            log.info("[RAG4] 활성화 — 추천 경로의 carComparison.narrative 를 LLM 으로 업그레이드");
        }
    }

    /**
     * 경로 리스트에서 추천 경로 1개에만 LLM narrative 적용.
     * @return 입력 순서 유지, 추천 경로만 narrative 가 교체된 새 리스트
     */
    @Observed(
            name = "navigation.rag.enhance_narrative",
            contextualName = "RAG 경로 설명 LLM 생성"
    )
    public List<Route> enhanceRecommended(List<Route> routes,
                                           Location origin,
                                           Location destination,
                                           String preference) {
        if (routes == null || routes.isEmpty()) return routes;
        if (narrativeGenerator == null) return routes; // 비활성화 상태

        return routes.stream()
                .map(r -> r.recommended() ? enhanceOne(r, origin, destination, preference) : r)
                .toList();
    }

    private Route enhanceOne(Route route, Location origin, Location destination, String preference) {
        try {
            // Retrieval: Qdrant 에서 유사 이력 조회
            List<ScoredRouteHistoryEntry> similar = fetchSimilar(route, origin, destination, preference);
            similarHitSummary.record(similar.size());

            // Augmented + Generation: LLM 호출 (boundedElastic 으로 블로킹 격리)
            String originalNarrative = route.carComparison() == null ? null : route.carComparison().narrative();
            // 타임아웃은 .timeout() 한 곳에서만 관리.
            // blockOptional(LLM_TIMEOUT) 로 이중 설정하면 바깥 타임아웃이 먼저 만료될 때
            // 내부 Mono 가 취소되지 않은 채 boundedElastic 스레드를 계속 점유한다 (스레드 누수).
            String llmNarrative = Mono.fromCallable(() ->
                            narrativeGenerator.generate(
                                    route, origin, destination, preference, similar, originalNarrative))
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(LLM_TIMEOUT)
                    .onErrorResume(e -> {
                        log.warn("[RAG4] LLM 타임아웃/실패 → 원본 narrative 유지. err={}", e.getMessage());
                        return Mono.just("");
                    })
                    .blockOptional()   // Spring MVC 동기 컨텍스트 전용 — 스트림(Flux) 경로에서 호출 금지
                    .orElse("");

            if (llmNarrative == null || llmNarrative.isBlank()) {
                return route; // 폴백: 원본 유지
            }
            return route.withCarComparison(replaceNarrative(route.carComparison(), llmNarrative));
        } catch (Exception e) {
            log.warn("[RAG4] enhanceOne 예외 — 원본 유지. routeId={}, err={}",
                    route.routeId(), e.getMessage());
            return route;
        }
    }

    private List<ScoredRouteHistoryEntry> fetchSimilar(Route route,
                                                       Location origin,
                                                       Location destination,
                                                       String preference) {
        if (routeHistoryPort == null) return List.of();
        try {
            // 쿼리 = 경로의 서술 요약 (RAG-2 에서 이미 임베딩된 형식과 유사)
            String query = String.format(
                    "%s에서 %s까지 %s 선호 경로",
                    origin.name() == null ? "" : origin.name(),
                    destination.name() == null ? "" : destination.name(),
                    "TIME_PRIORITY".equalsIgnoreCase(preference) ? "빠른 시간" : "안정적"
            );

            // 공간 필터: 같은 OD 격자의 이력만 — 타지역 사례가 narrative 근거로 오염되는 것 방지.
            // geohash 변환은 infra 어댑터 책임 (application 은 좌표만 전달).
            RagSearchRequest request = RagSearchRequest.nearLocations(
                    query, SIMILAR_TOP_K, SIMILAR_MIN_SCORE,
                    origin, destination, List.of()
            );
            List<ScoredRouteHistoryEntry> results = routeHistoryPort.search(request);
            log.debug("[RAG4] similar history hit: {} 건", results.size());
            return results;
        } catch (Exception e) {
            log.warn("[RAG4] similar history 조회 실패 — 빈 리스트로 진행. err={}", e.getMessage());
            return List.of();
        }
    }

    private static RouteComparison replaceNarrative(RouteComparison original, String newNarrative) {
        if (original == null) {
            // carComparison 이 원래 없었으면 만들지 않음 (기존 계약 유지)
            return null;
        }
        // RouteComparison 의 구조에 맞춰 narrative 만 교체
        return original.withNarrative(newNarrative);
    }
}
