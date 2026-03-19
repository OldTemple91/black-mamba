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
        Leg walkLeg = new Leg(LegType.WALK, "WALK", 5, 400, a, b, null, null, null);

        List<Location> candidates = selector.select(List.of(walkLeg), mobilityConfig());

        assertThat(candidates).isEmpty();
    }

    @Test
    void 목적지까지_거리가_범위_초과인_정류장은_제외한다() {
        Location farStop = new Location("먼정류장", 37.6000, 127.0000);
        Location nearStop = new Location("가까운정류장", 37.5090, 127.0100);
        Location dest = new Location("목적지", 37.5040, 127.0050);

        List<Location> candidates = selector.filterByMobilityRange(
                List.of(farStop, nearStop), dest, MobilityType.KICKBOARD_SHARED);

        assertThat(candidates).containsOnly(nearStop);
    }

    @Test
    void 목적지와_너무_가까운_정류장은_후보에서_제외한다() {
        Location tooCloseStop = new Location("너무가까운정류장", 37.5042, 127.0052);
        Location validStop = new Location("유효정류장", 37.5090, 127.0100);
        Location dest = new Location("목적지", 37.5040, 127.0050);

        List<Location> candidates = selector.filterByMobilityFeasibility(
                List.of(tooCloseStop, validStop), dest, MobilityConfig.bike());

        assertThat(candidates).containsOnly(validStop);
    }

    @Test
    void 퍼스트마일_출발지_근처_0_30퍼센트_정류장을_반환한다() {
        Location origin = new Location("출발", 37.5, 126.9);

        // start=(37.5, 126.9), end=(37.59, 126.9) → 10개 보간 → latStep=0.01
        // 0~30% = 앞 3개: 37.5(~0m), 37.51(~1.1km), 37.52(~2.2km) → 모두 5000m 이내
        Leg leg = new Leg(LegType.TRANSIT, "지하철", 30, 10000,
                new Location("시작", 37.5, 126.9),
                new Location("끝",   37.59, 126.9),
                TransitInfo.of("지하철", "2호선", 10), null, null);

        MobilityConfig config = MobilityConfig.kickboard(); // maxRange=5000m

        List<Location> candidates = selector.selectFirstMile(origin, List.of(leg), config);

        assertThat(candidates).isNotEmpty();
        candidates.forEach(c -> {
            double dist = haversineMeters(origin.lat(), origin.lng(), c.lat(), c.lng());
            assertThat(dist).isLessThanOrEqualTo(5000.0);
            assertThat(dist).isGreaterThanOrEqualTo(500.0);
        });
    }

    @Test
    void 라스트마일_기본_후보가_없으면_가장_가까운_feasible_정류소를_fallback으로_선택한다() {
        Location destination = new Location("목적지", 37.5040, 127.0050);
        Leg leg = new Leg(
                LegType.TRANSIT,
                "BUS",
                20,
                5000,
                new Location("시작", 37.4950, 127.0000),
                new Location("끝", 37.5250, 127.0000),
                TransitInfo.of("140", "#0052A4", 10),
                null,
                null
        );

        List<Location> fallback = selector.selectLastMileFallback(destination, List.of(leg), MobilityConfig.bike());

        assertThat(fallback).isNotEmpty();
        fallback.forEach(stop -> {
            double dist = haversineMeters(stop.lat(), stop.lng(), destination.lat(), destination.lng());
            assertThat(dist).isLessThanOrEqualTo(10_000.0);
            assertThat(dist).isGreaterThanOrEqualTo(700.0);
        });
    }

    @Test
    void 라스트마일_fallback은_엄격_기준_후보가_없을때만_완화된_최소거리로_허브를_살린다() {
        Location destination = new Location("목적지", 37.5040, 127.0050);
        List<Location> passStops = List.of(
                new Location("완화후보", 37.5001, 127.0037), // 약 450m
                new Location("너무가까운후보", 37.5027, 127.0048) // 약 150m
        );
        Leg leg = new Leg(
                LegType.TRANSIT,
                "BUS",
                12,
                3_000,
                new Location("시작", 37.4950, 127.0000),
                new Location("끝", 37.5050, 127.0040),
                new TransitInfo("140", "#0052A4", 2, 0, passStops),
                null,
                null
        );

        List<Location> fallback = selector.selectLastMileFallback(destination, List.of(leg), MobilityConfig.bike());

        assertThat(fallback).extracting(Location::name).contains("완화후보");
        assertThat(fallback).extracting(Location::name).doesNotContain("너무가까운후보");
    }

    @Test
    void 퍼스트마일_허브_검색은_출발지와_가까운_순으로_정렬한다() {
        Location origin = new Location("출발", 37.5, 126.9);
        Leg leg = new Leg(
                LegType.TRANSIT,
                "지하철",
                30,
                10_000,
                new Location("시작", 37.5, 126.9),
                new Location("끝", 37.59, 126.9),
                TransitInfo.of("2호선", "#00A84D", 10),
                null,
                null
        );

        BaselineTransitHubSearchAdapter adapter = new BaselineTransitHubSearchAdapter(selector);
        List<Location> candidates = adapter.findFirstMilePrimaryCandidates(origin, List.of(leg), MobilityConfig.kickboard());

        assertThat(candidates).isNotEmpty();
        double previous = -1;
        for (Location candidate : candidates) {
            double current = haversineMeters(origin.lat(), origin.lng(), candidate.lat(), candidate.lng());
            if (previous >= 0) {
                assertThat(current).isGreaterThanOrEqualTo(previous);
            }
            previous = current;
        }
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
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 20, 5000, start, end, transitInfo, null, null);
        return List.of(leg);
    }

    private MobilityConfig mobilityConfig() {
        return new MobilityConfig(MobilityType.KICKBOARD_SHARED, 5000);
    }
}
