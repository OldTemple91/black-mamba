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

        assertThat(legs).isNotEmpty();
        assertThat(legs.get(0).type()).isEqualTo(LegType.TRANSIT);
    }

    @Test
    void 도보_subPath는_WALK_타입으로_변환된다() {
        OdsayRouteMapper mapper = new OdsayRouteMapper();

        OdsayRouteResponse.Path path = new OdsayRouteResponse.Path(
                new OdsayRouteResponse.PathInfo(10, 0, 0),
                List.of(new OdsayRouteResponse.SubPath(3, 10, 700, null, List.of()))
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
                List.of(new OdsayRouteResponse.SubPath(2, 20, 4200,
                        new OdsayRouteResponse.Lane("간선 140", "#0052A4"),
                        List.of(
                                new OdsayRouteResponse.Station("서울역", 126.9706, 37.5547),
                                new OdsayRouteResponse.Station("반포역", 127.0050, 37.5040)
                        )))
        );
        List<Leg> legs = mapper.toLegs(path);

        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).transitInfo()).isNotNull();
        assertThat(legs.get(0).transitInfo().lineName()).isEqualTo("간선 140");
        assertThat(legs.get(0).start().name()).isEqualTo("서울역");
        assertThat(legs.get(0).end().name()).isEqualTo("반포역");
    }

    private OdsayRouteResponse.Path buildSamplePath() {
        return new OdsayRouteResponse.Path(
                new OdsayRouteResponse.PathInfo(30, 2, 1500),
                List.of(
                        new OdsayRouteResponse.SubPath(1, 20, 4200,
                                new OdsayRouteResponse.Lane("간선 140", "#0052A4"),
                                List.of(
                                        new OdsayRouteResponse.Station("서울역", 126.9706, 37.5547),
                                        new OdsayRouteResponse.Station("반포역", 127.0050, 37.5040)
                                )),
                        new OdsayRouteResponse.SubPath(3, 10, 700, null, List.of())
                )
        );
    }
}
