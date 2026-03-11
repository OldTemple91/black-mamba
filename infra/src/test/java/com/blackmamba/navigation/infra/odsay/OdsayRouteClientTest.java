package com.blackmamba.navigation.infra.odsay;

import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.infra.odsay.dto.OdsayRouteResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OdsayRouteClientTest {

    @Test
    void ODsay_응답을_Leg_목록으로_변환한다() {
        OdsayRouteMapper mapper = new OdsayRouteMapper();

        OdsayRouteResponse.Path path = buildSamplePath();
        List<Leg> legs = mapper.toLegs(path);

        assertThat(legs).hasSize(2);  // TRANSIT 1개 + WALK 1개
        assertThat(legs.get(0).type()).isEqualTo(LegType.TRANSIT);
        assertThat(legs.get(1).type()).isEqualTo(LegType.WALK);
    }

    @Test
    void 도보_subPath는_WALK_타입으로_변환된다() {
        OdsayRouteMapper mapper = new OdsayRouteMapper();

        OdsayRouteResponse.Path path = new OdsayRouteResponse.Path(
                new OdsayRouteResponse.PathInfo(10, 0, 0),
                List.of(new OdsayRouteResponse.SubPath(3, 10, 700, 0, null, null))
        );
        List<Leg> legs = mapper.toLegs(path);

        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).type()).isEqualTo(LegType.WALK);
    }

    @Test
    void transitInfo가_있는_버스구간_Leg를_생성한다() {
        OdsayRouteMapper mapper = new OdsayRouteMapper();

        OdsayRouteResponse.Path path = new OdsayRouteResponse.Path(
                new OdsayRouteResponse.PathInfo(20, 1, 1250),
                List.of(new OdsayRouteResponse.SubPath(2, 20, 4200, 2,
                        List.of(new OdsayRouteResponse.Lane("간선 140", "#0052A4", null)),
                        new OdsayRouteResponse.PassStopList(List.of(
                                new OdsayRouteResponse.Station("서울역", 126.9706, 37.5547),
                                new OdsayRouteResponse.Station("반포역", 127.0050, 37.5040)
                        ))))
        );
        List<Leg> legs = mapper.toLegs(path);

        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).transitInfo()).isNotNull();
        assertThat(legs.get(0).transitInfo().lineName()).isEqualTo("간선 140");
        assertThat(legs.get(0).start().name()).isEqualTo("서울역");
        assertThat(legs.get(0).end().name()).isEqualTo("반포역");
        assertThat(legs.get(0).transitInfo().passThroughStations())
                .extracting(com.blackmamba.navigation.domain.location.Location::name)
                .containsExactly("서울역", "반포역");
    }

    @Test
    void passStopList에_중간_정류장이_있으면_passThroughStations에_모두_포함된다() {
        OdsayRouteMapper mapper = new OdsayRouteMapper();

        OdsayRouteResponse.Path path = new OdsayRouteResponse.Path(
                new OdsayRouteResponse.PathInfo(30, 1, 1250),
                List.of(new OdsayRouteResponse.SubPath(1, 25, 10000, 4,
                        List.of(new OdsayRouteResponse.Lane("2호선", "#00A84D", null)),
                        new OdsayRouteResponse.PassStopList(List.of(
                                new OdsayRouteResponse.Station("강남역", 127.0276, 37.4979),
                                new OdsayRouteResponse.Station("역삼역", 127.0360, 37.5007),
                                new OdsayRouteResponse.Station("선릉역", 127.0490, 37.5047),
                                new OdsayRouteResponse.Station("삼성역", 127.0632, 37.5088)
                        ))))
        );
        List<Leg> legs = mapper.toLegs(path);

        assertThat(legs.get(0).transitInfo().stationCount()).isEqualTo(4);
        assertThat(legs.get(0).transitInfo().passThroughStations())
                .extracting(com.blackmamba.navigation.domain.location.Location::name)
                .containsExactly("강남역", "역삼역", "선릉역", "삼성역");
    }

    private OdsayRouteResponse.Path buildSamplePath() {
        return new OdsayRouteResponse.Path(
                new OdsayRouteResponse.PathInfo(30, 2, 1500),
                List.of(
                        new OdsayRouteResponse.SubPath(1, 20, 4200, 2,
                                List.of(new OdsayRouteResponse.Lane("간선 140", "#0052A4", null)),
                                new OdsayRouteResponse.PassStopList(List.of(
                                        new OdsayRouteResponse.Station("서울역", 126.9706, 37.5547),
                                        new OdsayRouteResponse.Station("반포역", 127.0050, 37.5040)
                                ))),
                        new OdsayRouteResponse.SubPath(3, 10, 700, 0, null, null)
                )
        );
    }
}
