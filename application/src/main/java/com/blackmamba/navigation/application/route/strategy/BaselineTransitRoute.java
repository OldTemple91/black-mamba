package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteType;

import java.util.List;

/**
 * 모든 혼합 패턴의 비교 기준이 되는 "대중교통만" 경로.
 * <p>
 * 대중교통 API 결과가 비어 있으면 haversine 추정 소요시간으로 단일 Leg 를 만든다 —
 * 그래야 후속 점수 계산(baseMinutes 대비 절감)이 0 으로 무너지지 않는다.
 *
 * @param legs      기준 경로 Leg (실제 또는 추정 1건)
 * @param minutes   기준 소요시간
 * @param estimated API 결과 없이 추정으로 만든 경우 true
 */
record BaselineTransitRoute(List<Leg> legs, int minutes, boolean estimated) {

    static BaselineTransitRoute from(List<Leg> baseLegs, Location origin, Location destination) {
        if (baseLegs.isEmpty()) {
            int minutes = TransitLegFactory.haversineTransitMinutes(origin, destination);
            return new BaselineTransitRoute(List.of(TransitLegFactory.plain(minutes, origin, destination)), minutes, true);
        }
        int minutes = baseLegs.stream().mapToInt(Leg::durationMinutes).sum();
        return new BaselineTransitRoute(baseLegs, minutes, false);
    }

    Route toRoute() {
        return Route.of(legs, RouteType.TRANSIT_ONLY);
    }
}
