package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DdareungiStationFilterTest {

    private final DdareungiStationFilter filter = new DdareungiStationFilter();

    @Test
    void 반경_내_따릉이_대여소를_필터링한다() {
        List<DdareungiStation> stations = List.of(
                new DdareungiStation("반포역 1번출구", 37.5038, 127.0048, 5),
                new DdareungiStation("반포한강공원", 37.5180, 127.0000, 3),  // 반경 밖
                new DdareungiStation("반포역 2번출구", 37.5040, 127.0052, 0) // 잔여대수 0
        );

        List<DdareungiStation> nearby = filter.filterNearby(stations, 37.5040, 127.0050, 300);

        assertThat(nearby).hasSize(1);
        assertThat(nearby.get(0).stationName()).isEqualTo("반포역 1번출구");
    }

    @Test
    void 잔여대수가_0인_대여소는_제외한다() {
        List<DdareungiStation> stations = List.of(
                new DdareungiStation("반포역", 37.5038, 127.0048, 0),
                new DdareungiStation("반포역 근처", 37.5039, 127.0049, 0)
        );

        List<DdareungiStation> nearby = filter.filterNearby(stations, 37.5040, 127.0050, 300);

        assertThat(nearby).isEmpty();
    }
}
