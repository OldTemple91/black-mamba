package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.location.Location;

/**
 * 대중교통·복합 경로 탐색을 시도할 최소 거리 정책.
 * <p>
 * 출발지와 목적지가 너무 가까우면 대중교통 API 가 유효한 경로를 주지 못하고
 * (대부분 도보가 정답) 외부 호출만 낭비한다. 이 기준은 API 입력 검증(400 응답)과
 * 외부 API 호출 생략 양쪽에서 같은 값을 써야 하므로 domain 에 한 곳으로 둔다.
 */
public final class TransitSearchDistancePolicy {

    /** 이 거리 이하는 대중교통 탐색 대상이 아니다 (도보 권장). */
    public static final double MIN_TRANSIT_DISTANCE_METERS = 700.0;

    private TransitSearchDistancePolicy() {
    }

    public static boolean isTooCloseForTransit(Location origin, Location destination) {
        return GeoDistance.meters(origin, destination) <= MIN_TRANSIT_DISTANCE_METERS;
    }
}
