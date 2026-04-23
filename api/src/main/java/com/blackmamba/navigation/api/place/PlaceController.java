package com.blackmamba.navigation.api.place;

import com.blackmamba.navigation.infra.naver.NaverGeocodingClient;
import com.blackmamba.navigation.infra.naver.NaverLocalSearchClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 장소 키워드 검색 API — POI 우선 + 주소/지번 폴백 (A-6).
 *
 * <h3>체인 흐름</h3>
 * <pre>
 *   1차: NaverLocalSearchClient (developers.naver.com 지역검색)
 *        — "강남역", "스타벅스 강남" 같은 POI/상호명에 강함
 *
 *   2차: NaverGeocodingClient.suggest() (NCP 지오코딩)
 *        — 1차가 빈 결과일 때만 호출
 *        — "서초구 반포동 45", "판교테크노밸리" 같은 주소/지번에 강함
 * </pre>
 * <p>
 * 이 이중 폴백은 {@code RouteListPage} 의 좌표 변환 로직과 같은 설계 원칙을
 * 자동완성에도 적용한 것. 기존 불일치(결과 페이지는 폴백, 자동완성은 POI 만)를 해소.
 */
@Tag(name = "장소 검색", description = "POI 키워드 + 주소/지번 폴백 검색")
@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private static final Logger log = LoggerFactory.getLogger(PlaceController.class);
    private static final int DISPLAY_COUNT = 5;

    private final NaverLocalSearchClient naverLocalSearchClient;
    private final NaverGeocodingClient naverGeocodingClient;

    public PlaceController(NaverLocalSearchClient naverLocalSearchClient,
                           NaverGeocodingClient naverGeocodingClient) {
        this.naverLocalSearchClient = naverLocalSearchClient;
        this.naverGeocodingClient = naverGeocodingClient;
    }

    @Operation(
            summary = "장소 키워드 검색 (POI + 주소 폴백)",
            description = """
                    1차 — 네이버 지역검색(POI) 으로 상호/지명 매칭.
                    2차 — 결과 없으면 네이버 지오코딩으로 주소/지번 검색.
                    최소 2글자 이상 입력 시 동작.
                    """
    )
    @GetMapping
    public Mono<List<NaverLocalSearchClient.PlaceItem>> search(@RequestParam String query) {
        if (query == null || query.isBlank() || query.length() < 2) {
            return Mono.just(List.of());
        }

        return naverLocalSearchClient.searchPlaces(query, DISPLAY_COUNT)
                .flatMap(local -> {
                    if (!local.isEmpty()) {
                        return Mono.just(local);
                    }
                    log.debug("[Places] POI 결과 없음 → Geocoding 폴백. query=\"{}\"", query);
                    return naverGeocodingClient.suggest(query)
                            .map(PlaceController::toPlaceItems);
                });
    }

    /** Geocoding SuggestItem → LocalSearch PlaceItem 통일 포맷 변환 */
    private static List<NaverLocalSearchClient.PlaceItem> toPlaceItems(
            List<NaverGeocodingClient.SuggestItem> suggestions) {
        return suggestions.stream()
                .map(s -> new NaverLocalSearchClient.PlaceItem(s.name(), s.lat(), s.lng()))
                .toList();
    }
}
