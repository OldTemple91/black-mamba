package com.blackmamba.navigation.infra.naver;

import com.blackmamba.navigation.application.route.port.PlaceResolverPort;
import com.blackmamba.navigation.domain.location.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/**
 * 장소명 → 좌표 해석 어댑터. 2단계 폴백:
 * <ol>
 *   <li>네이버 지오코딩 (주소 정확 일치)</li>
 *   <li>네이버 장소검색 (POI, "강남역" 같은 역/상호 매칭)</li>
 * </ol>
 * 타임아웃·오류는 여기서 {@code Optional.empty()} 로 정규화한다 —
 * 호출자가 origin/destination 을 {@code Mono.zip} 으로 병렬 해석해도 한쪽 실패가
 * 전체를 끊지 않는다.
 */
@Component
public class NaverPlaceResolverAdapter implements PlaceResolverPort {

    private static final Logger log = LoggerFactory.getLogger(NaverPlaceResolverAdapter.class);
    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(5);
    private static final int POI_DISPLAY_COUNT = 1;

    private final NaverGeocodingClient geocodingClient;
    private final NaverLocalSearchClient localSearchClient;

    public NaverPlaceResolverAdapter(NaverGeocodingClient geocodingClient,
                                     NaverLocalSearchClient localSearchClient) {
        this.geocodingClient = geocodingClient;
        this.localSearchClient = localSearchClient;
    }

    @Override
    public Mono<Optional<Location>> resolve(String placeName) {
        if (placeName == null || placeName.isBlank()) {
            return Mono.just(Optional.empty());
        }

        Mono<Location> byGeocode = geocodingClient.geocode(placeName)
                .filter(Optional::isPresent)
                .map(opt -> {
                    double[] latLng = opt.get();
                    return new Location(placeName, latLng[0], latLng[1]);
                });

        Mono<Location> byPlaceSearch = Mono.defer(() -> localSearchClient.searchPlaces(placeName, POI_DISPLAY_COUNT)
                .filter(list -> !list.isEmpty())
                .map(list -> new Location(placeName, list.getFirst().lat(), list.getFirst().lng())));

        return byGeocode.switchIfEmpty(byPlaceSearch)
                .timeout(RESOLVE_TIMEOUT)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    log.warn("[PlaceResolver] 장소 해석 실패: \"{}\" — {}", placeName, e.getMessage());
                    return Mono.just(Optional.empty());
                });
    }
}
