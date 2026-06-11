package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.RecommendationPreference;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.Route;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 경로 탐색 전략.
 * <p>
 * 요청별 상태(이동수단 목록, 선호도)는 생성자가 아닌 {@code search} 파라미터로 받는다
 * — 전략 인스턴스를 싱글톤으로 재사용해 요청마다 객체를 생성하지 않기 위함.
 */
public interface RouteSearchStrategy {

    /**
     * @param mobilityTypes SPECIFIC 모드에서 탐색할 이동수단 (OPTIMAL 모드는 무시 — 자동 선택)
     * @param preference    추천 선호도 (RELIABILITY / TIME_PRIORITY)
     */
    Mono<List<Route>> search(Location origin, Location destination,
                             List<MobilityType> mobilityTypes,
                             RecommendationPreference preference);
}
