package com.blackmamba.navigation.api.route;

import com.blackmamba.navigation.application.route.RouteOptimizationService;
import com.blackmamba.navigation.application.route.SearchMode;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.Route;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/routes")
public class RouteController {

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
            @RequestParam(defaultValue = "SPECIFIC") SearchMode searchMode
    ) {
        Location origin      = new Location("출발지", originLat, originLng);
        Location destination = new Location("목적지", destLat, destLng);

        List<MobilityType> mobilityTypes = mobility.stream()
                .filter(m -> !m.isBlank())
                .map(MobilityType::valueOf)
                .toList();

        List<Route> routes = routeOptimizationService
                .findRoutes(origin, destination, mobilityTypes, searchMode)
                .block();

        return ResponseEntity.ok(Map.of("routes", routes));
    }
}
