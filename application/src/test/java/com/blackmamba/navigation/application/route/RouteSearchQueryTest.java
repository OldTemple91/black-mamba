package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.accessibility.AccessibilityContext;
import com.blackmamba.navigation.application.route.weather.WeatherContext;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 탐색 요청의 진입 규칙 — /api/routes, 스트림, 자연어 검색이 모두 이 생성자를 거친다.
 */
class RouteSearchQueryTest {

    private final Location seoulStation = new Location("서울역", 37.5547, 126.9706);
    private final Location gangnam = new Location("강남역", 37.4979, 127.0276);

    @ParameterizedTest
    @CsvSource({
            "100.0, 127.0, INVALID_LATITUDE",
            "-90.1, 127.0, INVALID_LATITUDE",
            "37.5, 180.1, INVALID_LONGITUDE",
            "37.5, -999.0, INVALID_LONGITUDE",
    })
    void 좌표가_범위를_벗어나면_사유_코드와_함께_거절한다(double lat, double lng, String expectedCode) {
        // when & then
        assertThatThrownBy(() -> RouteSearchQuery.of(new Location("출발지", lat, lng), gangnam, null, SearchMode.OPTIMAL, null))
                .isInstanceOfSatisfying(InvalidRouteSearchException.class, e -> assertThat(e.code()).isEqualTo(expectedCode));
    }

    @Test
    void 목적지_좌표도_같은_규칙으로_검증한다() {
        // when & then
        assertThatThrownBy(() -> RouteSearchQuery.of(seoulStation, new Location("목적지", 37.5, 200.0), null, SearchMode.OPTIMAL, null))
                .isInstanceOfSatisfying(InvalidRouteSearchException.class, e -> assertThat(e.code()).isEqualTo("INVALID_LONGITUDE"));
    }

    @Test
    void 출발지와_목적지가_700m_이내면_거절한다() {
        // given — 약 170m
        Location nearby = new Location("근처", 37.5562, 126.9711);

        // when & then
        assertThatThrownBy(() -> RouteSearchQuery.of(seoulStation, nearby, null, SearchMode.OPTIMAL, null))
                .isInstanceOfSatisfying(InvalidRouteSearchException.class, e -> assertThat(e.code()).isEqualTo("SHORT_DISTANCE"));
    }

    @Test
    void 생략한_조건은_기본값으로_채운다() {
        // when
        RouteSearchQuery query = new RouteSearchQuery(seoulStation, gangnam, null, SearchMode.OPTIMAL, null, null, null);

        // then
        assertThat(query.mobilityTypes()).isEmpty();
        assertThat(query.preference()).isEqualTo(RecommendationPreference.RELIABILITY);
        assertThat(query.accessibility()).isEqualTo(AccessibilityContext.DEFAULT);
        assertThat(query.weather()).isEqualTo(WeatherContext.DEFAULT);
    }
}
