package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidatePointSelectorTest {

    private final CandidatePointSelector selector = new CandidatePointSelector();

    @Test
    void 전체_경로의_30_80퍼센트_구간_정류장만_후보로_선택한다() {
        List<Leg> legs = createLegsWithStops(10); // transitInfo.stationCount=10

        List<Location> candidates = selector.select(legs, mobilityConfig());

        // 10개 정류장 중 30~80% → from=3, to=8 → 5개
        assertThat(candidates.size()).isBetween(3, 6);
    }

    @Test
    void TRANSIT_구간이_없으면_빈_리스트를_반환한다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.4, 127.1);
        Leg walkLeg = new Leg(LegType.WALK, "WALK", 5, 400, a, b, null, null);

        List<Location> candidates = selector.select(List.of(walkLeg), mobilityConfig());

        assertThat(candidates).isEmpty();
    }

    @Test
    void 목적지까지_거리가_범위_초과인_정류장은_제외한다() {
        Location farStop = new Location("먼정류장", 37.6000, 127.0000);
        Location nearStop = new Location("가까운정류장", 37.5050, 127.0100);
        Location dest = new Location("목적지", 37.5040, 127.0050);

        List<Location> candidates = selector.filterByMobilityRange(
                List.of(farStop, nearStop), dest, MobilityType.KICKBOARD_SHARED);

        assertThat(candidates).containsOnly(nearStop);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private List<Leg> createLegsWithStops(int count) {
        Location start = new Location("출발", 37.5000, 127.0000);
        Location end   = new Location("도착", 37.5090, 127.0000);
        TransitInfo transitInfo = new TransitInfo("140", "#0052A4", count);
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 20, 5000, start, end, transitInfo, null);
        return List.of(leg);
    }

    private MobilityConfig mobilityConfig() {
        return new MobilityConfig(MobilityType.KICKBOARD_SHARED, 5000);
    }
}
