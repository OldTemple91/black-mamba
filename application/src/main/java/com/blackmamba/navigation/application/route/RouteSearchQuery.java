package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.InvalidRouteSearchException.Reason;
import com.blackmamba.navigation.application.route.accessibility.AccessibilityContext;
import com.blackmamba.navigation.application.route.weather.WeatherContext;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.TransitSearchDistancePolicy;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;

import java.util.List;

/**
 * 경로 탐색 한 번의 입력 — 만들어졌다면 탐색 가능한 요청이다.
 * <p>
 * 좌표 범위와 최소 거리(700m) 규칙이 /api/routes, /api/routes/stream, 자연어 검색 컨트롤러에 각각 복사돼 있었고,
 * 자연어 검색에는 거리 규칙이 빠져 있었다. 진입 경로가 몇 개든 규칙은 이 생성자 한 곳에서 검증한다.
 */
public record RouteSearchQuery(
        Location origin,
        Location destination,
        List<MobilityType> mobilityTypes,
        SearchMode searchMode,
        RecommendationPreference preference,
        AccessibilityContext accessibility,
        WeatherContext weather
) {
    public RouteSearchQuery {
        requireValidCoordinates(origin);
        requireValidCoordinates(destination);
        if (TransitSearchDistancePolicy.isTooCloseForTransit(origin, destination)) {
            throw new InvalidRouteSearchException(Reason.SHORT_DISTANCE,
                    "출발지와 목적지가 " + (int) TransitSearchDistancePolicy.MIN_TRANSIT_DISTANCE_METERS
                            + "m 이내라 현재 대중교통/복합 경로 탐색을 지원하지 않습니다. 도보 이동을 이용하거나 더 먼 목적지를 검색해 주세요.");
        }
        if (mobilityTypes == null) {
            mobilityTypes = List.of();
        } else {
            mobilityTypes = List.copyOf(mobilityTypes);
        }
        if (preference == null) {
            preference = RecommendationPreference.RELIABILITY;
        }
        if (accessibility == null) {
            accessibility = AccessibilityContext.DEFAULT;
        }
        if (weather == null) {
            weather = WeatherContext.DEFAULT;
        }
    }

    /** 접근성·날씨 조건이 없는 기본 탐색. */
    public static RouteSearchQuery of(Location origin, Location destination, List<MobilityType> mobilityTypes,
                                      SearchMode searchMode, RecommendationPreference preference) {
        return new RouteSearchQuery(origin, destination, mobilityTypes, searchMode, preference, AccessibilityContext.DEFAULT, WeatherContext.DEFAULT);
    }

    private static void requireValidCoordinates(Location location) {
        if (location.lat() < -90 || location.lat() > 90) {
            throw new InvalidRouteSearchException(Reason.INVALID_LATITUDE, "위도는 -90 ~ 90 범위여야 합니다.");
        }
        if (location.lng() < -180 || location.lng() > 180) {
            throw new InvalidRouteSearchException(Reason.INVALID_LONGITUDE, "경도는 -180 ~ 180 범위여야 합니다.");
        }
    }
}
