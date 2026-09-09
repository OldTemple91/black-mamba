package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.application.route.port.MobilityTimePort;
import com.blackmamba.navigation.application.route.port.TransitRoutePort;
import com.blackmamba.navigation.application.route.strategy.OptimalSearchStrategy;
import com.blackmamba.navigation.application.route.strategy.RouteSearchStrategy;
import com.blackmamba.navigation.application.route.strategy.SpecificMobilityStrategy;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 탐색 모드 → 전략 매핑.
 * <p>
 * 전략은 stateless — 요청별 상태(preference, mobilityTypes)는 {@code search()} 파라미터로 전달되므로
 * 앱 기동 시 한 번만 만들어 싱글톤으로 보유한다 (매 요청 객체 생성 없음).
 * 전략의 의존 그래프(포트 3종 + 허브 선택기 + 평가기)를 아는 곳은 여기뿐이다 —
 * 유스케이스({@code RouteOptimizationService})는 모드만 넘기고 전략을 받는다.
 */
@Component
public class RouteSearchStrategyRegistry {

    private final Map<SearchMode, RouteSearchStrategy> strategies = new EnumMap<>(SearchMode.class);

    public RouteSearchStrategyRegistry(TransitRoutePort transitRoutePort,
                                       MobilityTimePort mobilityTimePort,
                                       MobilityAvailabilityPort mobilityAvailabilityPort,
                                       HubSelector hubSelector,
                                       RouteEvaluator routeEvaluator) {
        strategies.put(SearchMode.OPTIMAL, new OptimalSearchStrategy(
                transitRoutePort, mobilityTimePort, mobilityAvailabilityPort, hubSelector, routeEvaluator));
        strategies.put(SearchMode.SPECIFIC, new SpecificMobilityStrategy(
                transitRoutePort, mobilityTimePort, mobilityAvailabilityPort, hubSelector, routeEvaluator));
    }

    public RouteSearchStrategy strategyFor(SearchMode searchMode) {
        RouteSearchStrategy strategy = strategies.get(searchMode);
        if (strategy == null) {
            throw new IllegalArgumentException("지원하지 않는 탐색 모드: " + searchMode);
        }
        return strategy;
    }
}
