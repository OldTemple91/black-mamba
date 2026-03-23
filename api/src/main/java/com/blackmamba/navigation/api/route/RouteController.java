package com.blackmamba.navigation.api.route;

import com.blackmamba.navigation.application.route.RouteOptimizationService;
import com.blackmamba.navigation.application.route.RecommendationPreference;
import com.blackmamba.navigation.application.route.SearchMode;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private static final Logger log = LoggerFactory.getLogger(RouteController.class);
    private static final double ODSAY_MIN_DISTANCE_METERS = 700.0;
    private static final Duration ROUTE_SEARCH_TIMEOUT = Duration.ofSeconds(30);
    private final RouteOptimizationService routeOptimizationService;

    public RouteController(RouteOptimizationService routeOptimizationService) {
        this.routeOptimizationService = routeOptimizationService;
    }

    /**
     * 멀티모달 경로 탐색 API
     *
     * GET /api/routes?originLat=37.5547&originLng=126.9706&destLat=37.4979&destLng=127.0276
     *                &mobility=KICKBOARD_SHARED&searchMode=OPTIMAL
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> searchRoutes(
            @RequestParam double originLat,
            @RequestParam double originLng,
            @RequestParam double destLat,
            @RequestParam double destLng,
            @RequestParam(defaultValue = "") List<String> mobility,
            @RequestParam(defaultValue = "SPECIFIC") SearchMode searchMode,
            @RequestParam(defaultValue = "RELIABILITY") RecommendationPreference recommendationPreference
    ) {
        // 입력값 검증
        if (originLat < -90 || originLat > 90 || destLat < -90 || destLat > 90) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "INVALID_LATITUDE",
                    "message", "위도는 -90 ~ 90 범위여야 합니다."
            ));
        }
        if (originLng < -180 || originLng > 180 || destLng < -180 || destLng > 180) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "INVALID_LONGITUDE",
                    "message", "경도는 -180 ~ 180 범위여야 합니다."
            ));
        }

        Location origin      = new Location("출발지", originLat, originLng);
        Location destination = new Location("목적지", destLat, destLng);

        if (distanceMeters(origin, destination) <= ODSAY_MIN_DISTANCE_METERS) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "SHORT_DISTANCE",
                    "message", "출발지와 목적지가 700m 이내라 현재 대중교통/복합 경로 탐색을 지원하지 않습니다. 도보 이동을 이용하거나 더 먼 목적지를 검색해 주세요."
            ));
        }

        List<MobilityType> mobilityTypes = mobility.stream()
                .filter(m -> !m.isBlank())
                .map(MobilityType::valueOf)
                .toList();

        try {
            List<Route> routes = routeOptimizationService
                    .findRoutes(origin, destination, mobilityTypes, searchMode, recommendationPreference)
                    .block(ROUTE_SEARCH_TIMEOUT);

            return ResponseEntity.ok(Map.of("routes", routes != null ? routes : List.of()));
        } catch (IllegalStateException e) {
            log.error("[경로 탐색] 타임아웃 ({}초 초과): {} → {}", ROUTE_SEARCH_TIMEOUT.getSeconds(),
                    origin.name(), destination.name());
            return ResponseEntity.status(504).body(Map.of(
                    "code", "SEARCH_TIMEOUT",
                    "message", "경로 탐색 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."
            ));
        }
    }

    private double distanceMeters(Location origin, Location destination) {
        double dLat = Math.toRadians(destination.lat() - origin.lat());
        double dLng = Math.toRadians(destination.lng() - origin.lng());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(origin.lat())) * Math.cos(Math.toRadians(destination.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
