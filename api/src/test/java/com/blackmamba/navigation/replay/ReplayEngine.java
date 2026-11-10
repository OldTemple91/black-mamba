package com.blackmamba.navigation.replay;

import com.blackmamba.navigation.application.route.CarbonMetrics;
import com.blackmamba.navigation.application.route.RouteEnrichmentPipeline;
import com.blackmamba.navigation.application.route.RouteEvaluator;
import com.blackmamba.navigation.application.route.RouteOptimizationService;
import com.blackmamba.navigation.application.route.RouteSearchQuery;
import com.blackmamba.navigation.application.route.RouteSearchStrategyRegistry;
import com.blackmamba.navigation.application.route.accessibility.AccessibilityPostProcessor;
import com.blackmamba.navigation.application.route.accessibility.AccessibilityStationRegistry;
import com.blackmamba.navigation.application.route.history.RouteHistoryRecorder;
import com.blackmamba.navigation.application.route.hub.BaselineTransitHubSearch;
import com.blackmamba.navigation.application.route.hub.CandidatePointSelector;
import com.blackmamba.navigation.application.route.hub.HubSelector;
import com.blackmamba.navigation.application.route.weather.WeatherAwareRouteAdjuster;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.comparison.CarReferenceCalculator;
import com.blackmamba.navigation.domain.route.comparison.CarbonFootprintCalculator;
import com.blackmamba.navigation.domain.route.evaluation.RouteInsightFactory;
import com.blackmamba.navigation.domain.route.evaluation.RouteScoreCalculator;
import com.blackmamba.navigation.infra.adapter.MobilityAvailabilityAdapter;
import com.blackmamba.navigation.infra.adapter.MobilityTimeAdapter;
import com.blackmamba.navigation.infra.adapter.OdsayTransitRouteAdapter;
import com.blackmamba.navigation.infra.common.cache.TtlCacheRegistry;
import com.blackmamba.navigation.infra.common.resilience.ExternalApiResilience;
import com.blackmamba.navigation.infra.ddareungi.DdareungiApiClient;
import com.blackmamba.navigation.infra.kickboard.KickboardApiClient;
import com.blackmamba.navigation.infra.odsay.OdsayRouteClient;
import com.blackmamba.navigation.infra.odsay.OdsayRouteMapper;
import com.blackmamba.navigation.infra.tmap.TmapPedestrianClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.mockito.Mockito.mock;

/**
 * 운영과 같은 객체 그래프를 Spring 없이 조립한다 — 실제 클라이언트·어댑터·전략·후처리 파이프라인.
 * 다른 것은 외부 API 주소뿐이다({@link ReplayApiServer}). 그래서 여기서 바뀌는 순위는 우리 코드가 바꾼 것이다.
 * <p>
 * 캐시 TTL 은 길게, 예산은 사실상 무제한으로 둔다. 이력 저장(RAG)은 끈다 — 순위와 무관하고 LLM·벡터 DB 가 필요하다.
 * <p>
 * 탐색 한 번에 하나씩 새로 만들어 쓴다. 경로·도보 캐시의 키는 좌표가 아니라 약 150m 격자라, 엔진을 케이스끼리 공유하면
 * 같은 격자에 든 다른 좌표의 응답을 먼저 돈 케이스가 채워 넣고, 결과가 케이스 실행 순서에 따라 달라진다.
 */
final class ReplayEngine {

    private static final long LONG_TTL_MS = Duration.ofHours(1).toMillis();
    private static final long REQUEST_TIMEOUT_MS = 20_000L;
    private static final int UNLIMITED_DAILY_BUDGET = 1_000_000;
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(120);

    private final RouteOptimizationService routeOptimizationService;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    ReplayEngine(String apiBaseUrl) {
        ExternalApiResilience resilience = new ExternalApiResilience(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()));
        TtlCacheRegistry cacheRegistry = new TtlCacheRegistry(meterRegistry);

        OdsayRouteClient odsayClient = new OdsayRouteClient(WebClient.builder(), new OdsayRouteMapper(), "replay-key",
                apiBaseUrl + ReplayApiServer.ODSAY_PREFIX, LONG_TTL_MS, REQUEST_TIMEOUT_MS, UNLIMITED_DAILY_BUDGET, 0.0,
                meterRegistry, resilience, cacheRegistry);
        DdareungiApiClient ddareungiClient = new DdareungiApiClient(WebClient.builder(), "replay-key",
                apiBaseUrl + ReplayApiServer.DDAREUNGI_PREFIX, LONG_TTL_MS, REQUEST_TIMEOUT_MS, 0L, LONG_TTL_MS, meterRegistry, resilience);
        TmapPedestrianClient tmapClient = new TmapPedestrianClient(WebClient.builder(), "replay-key",
                apiBaseUrl + ReplayApiServer.TMAP_PREFIX, LONG_TTL_MS, LONG_TTL_MS, REQUEST_TIMEOUT_MS, meterRegistry, resilience, cacheRegistry);
        KickboardApiClient kickboardClient = new KickboardApiClient(WebClient.builder(), "unused", "11", LONG_TTL_MS, REQUEST_TIMEOUT_MS,
                meterRegistry, resilience);

        OdsayTransitRouteAdapter transitAdapter = new OdsayTransitRouteAdapter(odsayClient);
        MobilityAvailabilityAdapter availabilityAdapter = new MobilityAvailabilityAdapter(ddareungiClient, kickboardClient, 700, LONG_TTL_MS, false,
                meterRegistry, cacheRegistry);
        MobilityTimeAdapter timeAdapter = new MobilityTimeAdapter(tmapClient);

        RouteEvaluator routeEvaluator = new RouteEvaluator(new RouteScoreCalculator(), new RouteInsightFactory());
        HubSelector hubSelector = new HubSelector(new BaselineTransitHubSearch(new CandidatePointSelector()));
        RouteSearchStrategyRegistry strategyRegistry = new RouteSearchStrategyRegistry(
                transitAdapter, timeAdapter, availabilityAdapter, hubSelector, routeEvaluator, transitAdapter);

        CarbonFootprintCalculator carbonFootprintCalculator = new CarbonFootprintCalculator();
        RouteEnrichmentPipeline enrichmentPipeline = new RouteEnrichmentPipeline(
                routeEvaluator,
                new AccessibilityPostProcessor(new AccessibilityStationRegistry()),
                new WeatherAwareRouteAdjuster(),
                carbonFootprintCalculator,
                new CarReferenceCalculator(carbonFootprintCalculator),
                new CarbonMetrics(meterRegistry));

        // 이력 포트가 없는 ObjectProvider — getIfAvailable() 이 null 이라 저장이 꺼진다
        RouteHistoryRecorder historyRecorder = new RouteHistoryRecorder(mock(ObjectProvider.class), meterRegistry);
        this.routeOptimizationService = new RouteOptimizationService(strategyRegistry, enrichmentPipeline, historyRecorder);
    }

    List<Route> search(RouteSearchQuery query) {
        return routeOptimizationService.findRoutes(query).block(SEARCH_TIMEOUT);
    }

    /**
     * 클라이언트가 응답을 못 쓰고 추정값으로 대체한 횟수. 파일은 있지만 읽지 못한 경우(응답 형식·매퍼가 바뀜)는
     * 서버 쪽에서는 정상 응답이라 여기서만 보인다. 0 이 아니면 그 순위는 실제 응답으로 만든 것이 아니다.
     */
    Map<String, Double> fallbackCounts() {
        Map<String, Double> counts = new TreeMap<>();
        for (Counter counter : meterRegistry.find("navigation.odsay.fallback.total").tag("reason", "error").counters()) {
            counts.merge("odsay", counter.count(), Double::sum);
        }
        for (Counter counter : meterRegistry.find("navigation.tmap.fallback.total").counters()) {
            counts.merge("tmap", counter.count(), Double::sum);
        }
        for (Counter counter : meterRegistry.find("navigation.ddareungi.fallback.total").counters()) {
            counts.merge("ddareungi", counter.count(), Double::sum);
        }
        for (Counter counter : meterRegistry.find("navigation.mobility.fallback.total").tag("reason", "error").counters()) {
            counts.merge("mobility", counter.count(), Double::sum);
        }
        counts.values().removeIf(count -> count == 0.0);
        return counts;
    }
}
