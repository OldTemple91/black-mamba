package com.blackmamba.navigation.infra.adapter;

import com.blackmamba.navigation.application.route.port.TransitRoutePort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.infra.odsay.OdsayRouteClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class OdsayTransitRouteAdapter implements TransitRoutePort {

    private final OdsayRouteClient odsayRouteClient;

    public OdsayTransitRouteAdapter(OdsayRouteClient odsayRouteClient) {
        this.odsayRouteClient = odsayRouteClient;
    }

    @Override
    public Mono<List<Leg>> getTransitRoute(Location origin, Location destination) {
        return odsayRouteClient.getTransitRoute(origin, destination);
    }

    @Override
    public Mono<Integer> getTransitTimeMinutes(Location origin, Location destination) {
        return odsayRouteClient.getTransitTimeMinutes(origin, destination);
    }
}
