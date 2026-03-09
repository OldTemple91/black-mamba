package com.blackmamba.navigation.api.place;

import com.blackmamba.navigation.infra.naver.NaverLocalSearchClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 장소 키워드 검색 API
 * GET /api/places?query=강남역
 *
 * 내부적으로 Naver 지역 검색 API(developers.naver.com)를 호출하여
 * POI 키워드로 장소명·좌표를 반환한다.
 */
@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final NaverLocalSearchClient naverLocalSearchClient;

    public PlaceController(NaverLocalSearchClient naverLocalSearchClient) {
        this.naverLocalSearchClient = naverLocalSearchClient;
    }

    @GetMapping
    public Mono<List<NaverLocalSearchClient.PlaceItem>> search(@RequestParam String query) {
        if (query == null || query.isBlank() || query.length() < 2) {
            return Mono.just(List.of());
        }
        return naverLocalSearchClient.searchPlaces(query, 5);
    }
}
