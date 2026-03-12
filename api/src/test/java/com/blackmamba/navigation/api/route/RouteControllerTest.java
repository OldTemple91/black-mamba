package com.blackmamba.navigation.api.route;

import com.blackmamba.navigation.application.route.RouteOptimizationService;
import com.blackmamba.navigation.application.route.RecommendationPreference;
import com.blackmamba.navigation.application.route.SearchMode;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RouteController.class)
class RouteControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean RouteOptimizationService routeOptimizationService;

    @Test
    void 경로_탐색_API가_200을_반환한다() throws Exception {
        RouteCostBreakdown breakdown = new RouteCostBreakdown(List.of(new CostComponent("대중교통", 1250)), 1250);
        RouteEvaluation evaluation = new RouteEvaluation(
                0.8, 0.9, 0.7, 0.8, 0.9, 0.85, 0.82,
                320, 1, 120,
                false, false, false, false,
                List.of(new RouteHub("서울역", com.blackmamba.navigation.domain.hub.HubType.SUBWAY_STATION,
                        "TRANSIT_BOARDING", "actual", java.util.Map.of()))
        );
        Route route = new Route("rt_001", RouteType.TRANSIT_ONLY, 45, 1250,
                breakdown, List.of(), evaluation, 0.5, true, List.of(), new Comparison(45, 0), null);

        when(routeOptimizationService.findRoutes(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(route)));

        mockMvc.perform(get("/api/routes")
                        .param("originLat", "37.5547")
                        .param("originLng", "126.9706")
                        .param("destLat", "37.4979")
                        .param("destLng", "127.0276"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].routeId").value("rt_001"))
                .andExpect(jsonPath("$.routes[0].totalMinutes").value(45))
                .andExpect(jsonPath("$.routes[0].costBreakdown.totalWon").value(1250))
                .andExpect(jsonPath("$.routes[0].costBreakdown.items[0].label").value("대중교통"))
                .andExpect(jsonPath("$.routes[0].evaluation.totalScore").value(0.82))
                .andExpect(jsonPath("$.routes[0].evaluation.hubs[0].type").value("SUBWAY_STATION"));
    }

    @Test
    void mobility_파라미터_없이도_경로를_탐색한다() throws Exception {
        RouteCostBreakdown breakdown = new RouteCostBreakdown(List.of(new CostComponent("대중교통", 1250)), 1250);
        Route route = new Route("rt_002", RouteType.TRANSIT_ONLY, 30, 1250,
                breakdown, List.of(), null, 0.6, true, List.of(), new Comparison(30, 0), null);

        when(routeOptimizationService.findRoutes(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(route)));

        mockMvc.perform(get("/api/routes")
                        .param("originLat", "37.5547")
                        .param("originLng", "126.9706")
                        .param("destLat", "37.4979")
                        .param("destLng", "127.0276"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes").isArray());
    }

    @Test
    void searchMode_OPTIMAL_파라미터로_경로를_탐색한다() throws Exception {
        RouteCostBreakdown breakdown = new RouteCostBreakdown(List.of(), 0);
        Route route = new Route("rt_opt", RouteType.MOBILITY_ONLY, 20, 0,
                breakdown, List.of(), null, 0.9, true, List.of(), null, null);

        when(routeOptimizationService.findRoutes(any(), any(), any(), eq(SearchMode.OPTIMAL), eq(RecommendationPreference.RELIABILITY)))
                .thenReturn(Mono.just(List.of(route)));

        mockMvc.perform(get("/api/routes")
                        .param("originLat", "37.5547")
                        .param("originLng", "126.9706")
                        .param("destLat", "37.4979")
                        .param("destLng", "127.0276")
                        .param("searchMode", "OPTIMAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].routeId").value("rt_opt"));
    }

    @Test
    void recommendationPreference_TIME_PRIORITY_파라미터로_경로를_탐색한다() throws Exception {
        RouteCostBreakdown breakdown = new RouteCostBreakdown(List.of(), 0);
        Route route = new Route("rt_pref", RouteType.TRANSIT_WITH_BIKE, 18, 1000,
                breakdown, List.of(), null, 0.91, true, List.of(), null, null);

        when(routeOptimizationService.findRoutes(any(), any(), any(), eq(SearchMode.OPTIMAL), eq(RecommendationPreference.TIME_PRIORITY)))
                .thenReturn(Mono.just(List.of(route)));

        mockMvc.perform(get("/api/routes")
                        .param("originLat", "37.5547")
                        .param("originLng", "126.9706")
                        .param("destLat", "37.4979")
                        .param("destLng", "127.0276")
                        .param("searchMode", "OPTIMAL")
                        .param("recommendationPreference", "TIME_PRIORITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].routeId").value("rt_pref"));
    }

    @Test
    void 출발지와_목적지가_700m_이내면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/routes")
                        .param("originLat", "37.5665")
                        .param("originLng", "126.9780")
                        .param("destLat", "37.5680")
                        .param("destLng", "126.9785"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHORT_DISTANCE"))
                .andExpect(jsonPath("$.message").exists());
    }
}
