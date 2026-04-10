package com.blackmamba.navigation.api.geocode;

import com.blackmamba.navigation.infra.naver.NaverGeocodingClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@Tag(name = "지오코딩", description = "주소→좌표 변환 및 장소명 자동완성")
@RestController
@RequestMapping("/api/geocode")
public class GeocodeController {

    private final NaverGeocodingClient naverGeocodingClient;

    public GeocodeController(NaverGeocodingClient naverGeocodingClient) {
        this.naverGeocodingClient = naverGeocodingClient;
    }

    @Operation(summary = "주소 → 좌표 변환", description = "네이버 지오코딩 API로 주소를 위도/경도로 변환")
    @GetMapping
    public Mono<ResponseEntity<CoordResponse>> geocode(@RequestParam String query) {
        return naverGeocodingClient.geocode(query)
                .map(opt -> opt
                        .map(latLng -> ResponseEntity.ok(new CoordResponse(latLng[0], latLng[1])))
                        .orElseGet(() -> ResponseEntity.ok(new CoordResponse(null, null)))
                )
                .onErrorReturn(ResponseEntity.ok(new CoordResponse(null, null)));
    }

    /**
     * 장소명 연관검색어 — GET /api/geocode/suggest?query=강남
     * 최소 2글자 이상일 때만 검색 (짧은 쿼리는 빈 배열 반환)
     */
    @Operation(summary = "장소명 자동완성", description = "최소 2글자 이상 입력 시 연관 장소 목록 반환")
    @GetMapping("/suggest")
    public Mono<List<NaverGeocodingClient.SuggestItem>> suggest(@RequestParam String query) {
        if (query == null || query.isBlank() || query.length() < 2) {
            return Mono.just(List.of());
        }
        return naverGeocodingClient.suggest(query);
    }

    public record CoordResponse(Double lat, Double lng) {}
}
