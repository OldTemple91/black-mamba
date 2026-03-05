package com.blackmamba.navigation.api.route;

import com.blackmamba.navigation.application.route.RouteOptimizationService;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RouteController.class)
class RouteControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean RouteOptimizationService routeOptimizationService;

    @Test
    void 경로_탐색_API가_200을_반환한다() throws Exception {
        Route route = new Route("rt_001", RouteType.TRANSIT_ONLY, 45, 1250,
                0.5, true, List.of(), new Comparison(45, 0));

        when(routeOptimizationService.findRoutes(any(), any(), any()))
                .thenReturn(Mono.just(List.of(route)));

        mockMvc.perform(get("/api/routes")
                        .param("originLat", "37.5547")
                        .param("originLng", "126.9706")
                        .param("destLat", "37.4979")
                        .param("destLng", "127.0276"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].routeId").value("rt_001"))
                .andExpect(jsonPath("$.routes[0].totalMinutes").value(45));
    }

    @Test
    void mobility_파라미터_없이도_경로를_탐색한다() throws Exception {
        Route route = new Route("rt_002", RouteType.TRANSIT_ONLY, 30, 1250,
                0.6, true, List.of(), new Comparison(30, 0));

        when(routeOptimizationService.findRoutes(any(), any(), any()))
                .thenReturn(Mono.just(List.of(route)));

        mockMvc.perform(get("/api/routes")
                        .param("originLat", "37.5547")
                        .param("originLng", "126.9706")
                        .param("destLat", "37.4979")
                        .param("destLng", "127.0276"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes").isArray());
    }
}
