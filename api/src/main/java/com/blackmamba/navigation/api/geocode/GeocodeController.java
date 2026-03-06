package com.blackmamba.navigation.api.geocode;

import com.blackmamba.navigation.infra.naver.NaverGeocodingClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/geocode")
public class GeocodeController {

    private final NaverGeocodingClient naverGeocodingClient;

    public GeocodeController(NaverGeocodingClient naverGeocodingClient) {
        this.naverGeocodingClient = naverGeocodingClient;
    }

    @GetMapping
    public Mono<ResponseEntity<CoordResponse>> geocode(@RequestParam String query) {
        return naverGeocodingClient.geocode(query)
                .map(opt -> opt
                        .map(latLng -> ResponseEntity.ok(new CoordResponse(latLng[0], latLng[1])))
                        .orElse(ResponseEntity.notFound().<CoordResponse>build())
                );
    }

    public record CoordResponse(double lat, double lng) {}
}
