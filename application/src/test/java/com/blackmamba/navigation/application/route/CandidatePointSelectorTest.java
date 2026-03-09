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

    @Test
    void 퍼스트마일_출발지_근처_0_30퍼센트_정류장을_반환한다() {
        Location origin = new Location("출발", 37.5, 126.9);

        // start=(37.5, 126.9), end=(37.59, 126.9) → 10개 보간 → latStep=0.01
        // 0~30% = 앞 3개: 37.5(~0m), 37.51(~1.1km), 37.52(~2.2km) → 모두 5000m 이내
        Leg leg = new Leg(LegType.TRANSIT, "지하철", 30, 10000,
                new Location("시작", 37.5, 126.9),
                new Location("끝",   37.59, 126.9),
                TransitInfo.of("지하철", "2호선", 10), null);

        MobilityConfig config = MobilityConfig.kickboard(); // maxRange=5000m

        List<Location> candidates = selector.selectFirstMile(origin, List.of(leg), config);

        assertThat(candidates).isNotEmpty();
        candidates.forEach(c -> {
            double dist = haversineMeters(origin.lat(), origin.lng(), c.lat(), c.lng());
            assertThat(dist).isLessThanOrEqualTo(5000.0);
        });
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                 + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng/2)*Math.sin(dLng/2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    private List<Leg> createLegsWithStops(int count) {
        Location start = new Location("출발", 37.5000, 127.0000);
        Location end   = new Location("도착", 37.5090, 127.0000);
        TransitInfo transitInfo = TransitInfo.of("140", "#0052A4", count);
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 20, 5000, start, end, transitInfo, null);
        return List.of(leg);
    }

    private MobilityConfig mobilityConfig() {
        return new MobilityConfig(MobilityType.KICKBOARD_SHARED, 5000);
    }
}
