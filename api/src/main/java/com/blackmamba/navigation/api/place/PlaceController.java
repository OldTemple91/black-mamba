package com.blackmamba.navigation.api.place;

import com.blackmamba.navigation.application.route.port.PlaceSearchPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 장소 키워드 검색 API — POI 우선 + 주소/지번 폴백 (A-6).
 * <p>
 * 폴백 체인은 {@link PlaceSearchPort} 어댑터가 담당한다. 이 이중 폴백은
 * {@code RouteListPage} 의 좌표 변환 로직과 같은 설계 원칙을 자동완성에도 적용한 것.
 */
@Tag(name = "장소 검색", description = "POI 키워드 + 주소/지번 폴백 검색")
@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private static final int DISPLAY_COUNT = 5;
    private static final int MIN_QUERY_LENGTH = 2;

    private final PlaceSearchPort placeSearchPort;

    public PlaceController(PlaceSearchPort placeSearchPort) {
        this.placeSearchPort = placeSearchPort;
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
    public Mono<List<PlaceResponse>> search(@RequestParam String query) {
        if (query == null || query.isBlank() || query.length() < MIN_QUERY_LENGTH) {
            return Mono.just(List.of());
        }
        return placeSearchPort.searchByKeyword(query, DISPLAY_COUNT)
                .map(PlaceResponse::fromAll);
    }
}
