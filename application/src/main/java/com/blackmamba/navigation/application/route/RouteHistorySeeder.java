package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.RouteHistoryPort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 데모/발표용 시드 데이터를 {@link RouteHistoryPort} 에 일괄 적재.
 * <p>
 * 운영 기동 시 자동 실행하지 않는다 — 관리자가 {@code POST /api/rag/admin/seed} 로 명시 트리거.
 *
 * <h3>시드 데이터 구성</h3>
 * 서울 주요 OD 페어 × 선호도/이동수단 조합 = 약 20건.
 * 실제 ODsay 호출 없이 <b>Route 스켈레톤</b>을 수동 구성.
 * Legs 는 최소 1개 TRANSIT/BIKE 만 — {@link com.blackmamba.navigation.infra.vector.RouteHistoryDescriber}
 * 가 이 정도 수준으로도 "지하철 직행/환승 1회/따릉이 조합" 을 충분히 서술.
 */
@Component
public class RouteHistorySeeder {

    private static final Logger log = LoggerFactory.getLogger(RouteHistorySeeder.class);

    private final RouteHistoryPort routeHistoryPort; // null 가능

    public RouteHistorySeeder(ObjectProvider<RouteHistoryPort> portProvider) {
        this.routeHistoryPort = portProvider.getIfAvailable();
    }

    /**
     * 시드 데이터를 모두 저장한다.
     * @return 실제 저장 시도된 건수
     */
    public int seed() {
        if (routeHistoryPort == null) {
            log.warn("[RAG] Seed 스킵 — RouteHistoryPort 빈 부재 (Qdrant 미기동?)");
            return 0;
        }

        List<SeedSample> samples = buildSamples();
        int saved = 0;
        for (SeedSample s : samples) {
            Route route = toRoute(s);
            routeHistoryPort.save(route, s.origin, s.destination, s.preference);
            saved++;
        }
        log.info("[RAG] Seed 완료 — {}건 저장", saved);
        return saved;
    }

    // ─── 내부: Sample → Route 변환 ─────────────────

    private Route toRoute(SeedSample s) {
        List<Leg> legs = new ArrayList<>();
        // Leg 1: TRANSIT (항상 포함)
        legs.add(new Leg(
                LegType.TRANSIT, s.transitMode, s.transitMinutes, s.transitDistanceMeters,
                s.origin, s.destination,
                new TransitInfo(s.transitLineName, null, s.stationCount, s.costWon, List.of()),
                null, null
        ));
        // Leg 2: 이동수단 라스트마일 (선택)
        if (s.mobility != null) {
            legs.add(new Leg(
                    LegType.BIKE, "BIKE", s.mobilityMinutes, s.mobilityDistanceMeters,
                    s.destination, s.destination,
                    null,
                    new MobilityInfo(s.mobility, "operator", null, 0, "목적지 근처", s.destination.lat(), s.destination.lng(), 5, 200),
                    null
            ));
        }
        // Route.of() 는 routeId=UUID, totalMinutes=합계 계산
        return Route.of(legs, s.routeType).withScore(0.85, /* recommended */ true);
    }

    // ─── 시드 데이터 정의 ─────────────────

    private record SeedSample(
            Location origin,
            Location destination,
            RouteType routeType,
            String transitMode,         // "SUBWAY" / "BUS"
            String transitLineName,     // "2호선" / "9호선" / "광역버스 9401"
            int stationCount,
            int transitMinutes,
            int transitDistanceMeters,
            int costWon,
            MobilityType mobility,      // null 이면 순수 대중교통
            int mobilityMinutes,
            int mobilityDistanceMeters,
            String preference           // "RELIABILITY" / "TIME_PRIORITY"
    ) {}

    private List<SeedSample> buildSamples() {
        // 서울 주요 지점 좌표 (실제 위치)
        Location gangnam = new Location("강남역", 37.4980, 127.0276);
        Location hongdae = new Location("홍대입구", 37.5570, 126.9240);
        Location seoul = new Location("서울역", 37.5547, 126.9706);
        Location jamsil = new Location("잠실역", 37.5133, 127.1000);
        Location yeouido = new Location("여의도역", 37.5216, 126.9243);
        Location pangyo = new Location("판교역", 37.3947, 127.1112);
        Location itaewon = new Location("이태원역", 37.5344, 126.9947);
        Location sillim = new Location("신림역", 37.4842, 126.9297);
        Location konkuk = new Location("건대입구", 37.5403, 127.0696);
        Location seongsu = new Location("성수역", 37.5446, 127.0559);

        List<SeedSample> list = new ArrayList<>();

        // ─── 강남 ↔ 홍대 (주요 축) ───
        list.add(new SeedSample(gangnam, hongdae, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 12, 36, 10500, 1650, null, 0, 0, "RELIABILITY"));
        list.add(new SeedSample(gangnam, hongdae, RouteType.MOBILITY_FIRST_TRANSIT,
                "SUBWAY", "2호선", 10, 30, 9000, 1650, MobilityType.DDAREUNGI, 5, 1200, "TIME_PRIORITY"));
        list.add(new SeedSample(hongdae, gangnam, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 12, 37, 10500, 1650, null, 0, 0, "RELIABILITY"));

        // ─── 서울역 기준 ───
        list.add(new SeedSample(seoul, jamsil, RouteType.TRANSIT_ONLY,
                "SUBWAY", "1호선+2호선", 14, 40, 12000, 1650, null, 0, 0, "RELIABILITY"));
        list.add(new SeedSample(seoul, gangnam, RouteType.TRANSIT_ONLY,
                "SUBWAY", "4호선+2호선", 10, 28, 8500, 1650, null, 0, 0, "RELIABILITY"));
        list.add(new SeedSample(seoul, hongdae, RouteType.TRANSIT_ONLY,
                "SUBWAY", "1호선+2호선", 8, 22, 6500, 1450, null, 0, 0, "TIME_PRIORITY"));
        list.add(new SeedSample(seoul, pangyo, RouteType.TRANSIT_ONLY,
                "BUS", "광역버스 9401", 18, 55, 22000, 2800, null, 0, 0, "RELIABILITY"));

        // ─── 강남 기준 ───
        list.add(new SeedSample(gangnam, pangyo, RouteType.TRANSIT_ONLY,
                "SUBWAY", "신분당선", 4, 15, 7000, 1800, null, 0, 0, "TIME_PRIORITY"));
        list.add(new SeedSample(gangnam, jamsil, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 6, 18, 5500, 1450, null, 0, 0, "TIME_PRIORITY"));
        list.add(new SeedSample(gangnam, itaewon, RouteType.MOBILITY_FIRST_TRANSIT,
                "SUBWAY", "6호선", 4, 20, 6000, 1450, MobilityType.DDAREUNGI, 8, 2000, "TIME_PRIORITY"));

        // ─── 홍대 기준 ───
        list.add(new SeedSample(hongdae, yeouido, RouteType.TRANSIT_ONLY,
                "SUBWAY", "9호선", 5, 14, 4000, 1450, null, 0, 0, "TIME_PRIORITY"));
        list.add(new SeedSample(hongdae, konkuk, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 10, 32, 9500, 1650, null, 0, 0, "RELIABILITY"));
        list.add(new SeedSample(hongdae, seongsu, RouteType.MOBILITY_FIRST_TRANSIT,
                "SUBWAY", "2호선", 11, 28, 8500, 1650, MobilityType.PERSONAL_EBIKE, 6, 1500, "TIME_PRIORITY"));

        // ─── 잠실/건대 축 ───
        list.add(new SeedSample(jamsil, gangnam, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 6, 17, 5500, 1450, null, 0, 0, "TIME_PRIORITY"));
        list.add(new SeedSample(konkuk, seongsu, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 2, 6, 2000, 1450, null, 0, 0, "TIME_PRIORITY"));
        list.add(new SeedSample(konkuk, gangnam, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 8, 22, 6500, 1450, null, 0, 0, "RELIABILITY"));

        // ─── 환승 적은 경로 선호 ───
        list.add(new SeedSample(sillim, gangnam, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 7, 20, 6000, 1450, null, 0, 0, "RELIABILITY"));
        list.add(new SeedSample(yeouido, seoul, RouteType.TRANSIT_ONLY,
                "SUBWAY", "9호선+1호선", 6, 18, 5500, 1650, null, 0, 0, "RELIABILITY"));

        // ─── 따릉이 퍼스트마일 시나리오 ───
        list.add(new SeedSample(seongsu, konkuk, RouteType.MOBILITY_ONLY,
                "BIKE", "따릉이", 0, 15, 3000, 1000, MobilityType.DDAREUNGI, 15, 3000, "TIME_PRIORITY"));
        list.add(new SeedSample(hongdae, seoul, RouteType.MOBILITY_FIRST_TRANSIT,
                "SUBWAY", "1호선", 5, 15, 4500, 1450, MobilityType.DDAREUNGI, 7, 1700, "TIME_PRIORITY"));

        return list;
    }
}
