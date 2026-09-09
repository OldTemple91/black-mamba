package com.blackmamba.navigation.infra.naver;

import com.blackmamba.navigation.application.route.port.PlaceSearchPort;
import com.blackmamba.navigation.domain.location.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * 장소 검색 어댑터 — POI 우선 + 주소/지번 폴백 (A-6).
 *
 * <h3>체인 흐름 (keyword 검색)</h3>
 * <pre>
 *   1차: NaverLocalSearchClient (developers.naver.com 지역검색)
 *        — "강남역", "스타벅스 강남" 같은 POI/상호명에 강함
 *   2차: NaverGeocodingClient.suggest() (NCP 지오코딩)
 *        — 1차가 빈 결과일 때만 호출 — "서초구 반포동 45" 같은 주소/지번에 강함
 * </pre>
 * 벤더 record({@code PlaceItem}, {@code SuggestItem})는 여기서 도메인 {@link Location} 으로
 * 변환되어 바깥으로 나가지 않는다.
 */
@Component
public class NaverPlaceSearchAdapter implements PlaceSearchPort {

    private static final Logger log = LoggerFactory.getLogger(NaverPlaceSearchAdapter.class);

    private final NaverLocalSearchClient localSearchClient;
    private final NaverGeocodingClient geocodingClient;

    public NaverPlaceSearchAdapter(NaverLocalSearchClient localSearchClient,
                                   NaverGeocodingClient geocodingClient) {
        this.localSearchClient = localSearchClient;
        this.geocodingClient = geocodingClient;
    }

    @Override
    public Mono<List<Location>> searchByKeyword(String keyword, int limit) {
        return localSearchClient.searchPlaces(keyword, limit)
                .flatMap(places -> {
                    if (!places.isEmpty()) {
                        return Mono.just(places.stream()
                                .map(item -> new Location(item.name(), item.lat(), item.lng()))
                                .toList());
                    }
                    log.debug("[PlaceSearch] POI 결과 없음 → Geocoding 폴백. keyword=\"{}\"", keyword);
                    return suggest(keyword);
                })
                .onErrorReturn(List.of());
    }

    @Override
    public Mono<Optional<Location>> geocode(String address) {
        return geocodingClient.geocode(address)
                .map(latLng -> latLng.map(coords -> new Location(address, coords[0], coords[1])))
                .onErrorReturn(Optional.empty());
    }

    @Override
    public Mono<List<Location>> suggest(String keyword) {
        return geocodingClient.suggest(keyword)
                .map(items -> items.stream()
                        .map(item -> new Location(item.name(), item.lat(), item.lng()))
                        .toList())
                .onErrorReturn(List.of());
    }
}
