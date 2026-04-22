package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.RouteHistoryPort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * 데모/발표용 시드 데이터를 {@link RouteHistoryPort} 에 일괄 적재.
 * <p>
 * 운영 기동 시 자동 실행하지 않는다 — 관리자가 {@code POST /api/rag/admin/seed} 로 명시 트리거.
 *
 * <h3>시드 구성 (RAG-6 확장)</h3>
 * <pre>
 *   서울 주요 OD 페어 20개
 *     × 시간대/요일 5개 (평일 아침 러시 / 평일 점심 / 평일 저녁 러시 / 평일 심야 / 주말 오후)
 *     × 선호도 2개 (RELIABILITY / TIME_PRIORITY)
 *   = 200건
 * </pre>
 * <p>
 * 각 시드는 과거 시각을 {@link RouteHistoryPort#save(Route, Location, Location, String, Instant)}
 * 로 주입해 맥락 태그("평일 저녁 러시아워" 등)가 임베딩 소스에 반영되도록 한다.
 * 이렇게 하면 벡터 공간 분산이 확대되고 "러시아워 빠른 경로" 같은
 * 시간 맥락 쿼리가 실효적으로 동작한다.
 */
@Component
public class RouteHistorySeeder {

    private static final Logger log = LoggerFactory.getLogger(RouteHistorySeeder.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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

        List<OdSpec> odPool = buildOdPool();
        List<Instant> timeSlots = buildTimeSlots();   // 5개
        String[] preferences = {"RELIABILITY", "TIME_PRIORITY"};

        int saved = 0;
        for (OdSpec od : odPool) {
            for (Instant slot : timeSlots) {
                for (String pref : preferences) {
                    Route route = toRoute(od, pref);
                    routeHistoryPort.save(route, od.origin, od.destination, pref, slot);
                    saved++;
                }
            }
        }
        log.info("[RAG] Seed 완료 — OD {}개 × 시간대 {}개 × 선호도 {}개 = {}건 저장",
                odPool.size(), timeSlots.size(), preferences.length, saved);
        return saved;
    }

    // ─── OD 풀 (서울 주요 생활권 20개) ───────────────

    private record OdSpec(
            Location origin,
            Location destination,
            RouteType routeType,
            String transitMode,
            String transitLineName,
            int stationCount,
            int transitMinutes,
            int transitDistanceMeters,
            int costWon,
            MobilityType mobility,      // null 이면 순수 대중교통
            int mobilityMinutes,
            int mobilityDistanceMeters
    ) {}

    private List<OdSpec> buildOdPool() {
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
        Location hapjeong = new Location("합정역", 37.5497, 126.9136);
        Location hannam = new Location("한남역", 37.5318, 127.0100);
        Location wangsimni = new Location("왕십리역", 37.5611, 127.0380);
        Location yongsan = new Location("용산역", 37.5296, 126.9644);
        Location gangbyeon = new Location("강변역", 37.5354, 127.0946);

        List<OdSpec> list = new ArrayList<>();

        // ── 주요 축 (강남/서울/홍대) ─────────────────
        list.add(new OdSpec(gangnam, hongdae, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 12, 36, 10500, 1650, null, 0, 0));
        list.add(new OdSpec(hongdae, gangnam, RouteType.MOBILITY_FIRST_TRANSIT,
                "SUBWAY", "2호선", 10, 30, 9000, 1650, MobilityType.DDAREUNGI, 5, 1200));
        list.add(new OdSpec(seoul, gangnam, RouteType.TRANSIT_ONLY,
                "SUBWAY", "4호선+2호선", 10, 28, 8500, 1650, null, 0, 0));
        list.add(new OdSpec(seoul, hongdae, RouteType.TRANSIT_ONLY,
                "SUBWAY", "1호선+2호선", 8, 22, 6500, 1450, null, 0, 0));
        list.add(new OdSpec(seoul, jamsil, RouteType.TRANSIT_ONLY,
                "SUBWAY", "1호선+2호선", 14, 40, 12000, 1650, null, 0, 0));

        // ── 강남 축 ─────────────────
        list.add(new OdSpec(gangnam, pangyo, RouteType.TRANSIT_ONLY,
                "SUBWAY", "신분당선", 4, 15, 7000, 1800, null, 0, 0));
        list.add(new OdSpec(gangnam, jamsil, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 6, 18, 5500, 1450, null, 0, 0));
        list.add(new OdSpec(gangnam, itaewon, RouteType.MOBILITY_FIRST_TRANSIT,
                "SUBWAY", "6호선", 4, 20, 6000, 1450, MobilityType.DDAREUNGI, 8, 2000));

        // ── 홍대 축 ─────────────────
        list.add(new OdSpec(hongdae, yeouido, RouteType.TRANSIT_ONLY,
                "SUBWAY", "9호선", 5, 14, 4000, 1450, null, 0, 0));
        list.add(new OdSpec(hongdae, konkuk, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 10, 32, 9500, 1650, null, 0, 0));
        list.add(new OdSpec(hongdae, seongsu, RouteType.MOBILITY_FIRST_TRANSIT,
                "SUBWAY", "2호선", 11, 28, 8500, 1650, MobilityType.PERSONAL_EBIKE, 6, 1500));
        list.add(new OdSpec(hongdae, hapjeong, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선+6호선", 2, 5, 1500, 1450, null, 0, 0));

        // ── 성수/건대 축 (자전거 친화) ─────────────────
        list.add(new OdSpec(seongsu, konkuk, RouteType.MOBILITY_ONLY,
                "BIKE", "따릉이", 0, 15, 3000, 1000, MobilityType.DDAREUNGI, 15, 3000));
        list.add(new OdSpec(konkuk, gangnam, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 8, 22, 6500, 1450, null, 0, 0));
        list.add(new OdSpec(wangsimni, seongsu, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 1, 4, 1500, 1450, null, 0, 0));

        // ── 서부/남부 ─────────────────
        list.add(new OdSpec(sillim, gangnam, RouteType.TRANSIT_ONLY,
                "SUBWAY", "2호선", 7, 20, 6000, 1450, null, 0, 0));
        list.add(new OdSpec(yeouido, seoul, RouteType.TRANSIT_ONLY,
                "SUBWAY", "9호선+1호선", 6, 18, 5500, 1650, null, 0, 0));
        list.add(new OdSpec(yongsan, gangnam, RouteType.TRANSIT_ONLY,
                "SUBWAY", "4호선+2호선", 8, 25, 7500, 1650, null, 0, 0));

        // ── 경유·광역 ─────────────────
        list.add(new OdSpec(seoul, pangyo, RouteType.TRANSIT_ONLY,
                "BUS", "광역버스 9401", 18, 55, 22000, 2800, null, 0, 0));
        list.add(new OdSpec(hannam, gangnam, RouteType.TRANSIT_ONLY,
                "BUS", "수도권 광역 402", 8, 18, 5500, 1450, null, 0, 0));

        // 정확히 20개 확인
        if (list.size() != 20) {
            log.warn("[RAG] OD 풀 크기 예상(20) != 실제({}) — 시드 총건수가 변동합니다", list.size());
        }
        return list;
    }

    // ─── 시간대 슬롯 (고정 과거 시점) ───────────────

    /**
     * 맥락 태그의 5가지 조합을 확보하기 위한 고정 시점.
     * RouteHistoryDescriber.contextTag() 가 읽는 Asia/Seoul 기준.
     */
    private List<Instant> buildTimeSlots() {
        // 가장 가까운 과거 평일(월) 기준으로 시간대 4개
        LocalDate lastMonday = LocalDate.now(KST)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                // 오늘이 월이면 이전 주 월요일로 밀어 "과거" 보장
                .minus(7, ChronoUnit.DAYS);
        // 가장 가까운 과거 토요일
        LocalDate lastSaturday = LocalDate.now(KST)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
                .minus(7, ChronoUnit.DAYS);

        List<Instant> slots = new ArrayList<>();
        slots.add(atTime(lastMonday, 8));    // 평일 아침 러시아워
        slots.add(atTime(lastMonday, 12));   // 평일 점심
        slots.add(atTime(lastMonday, 18));   // 평일 저녁 러시아워
        slots.add(atTime(lastMonday, 23));   // 평일 심야
        slots.add(atTime(lastSaturday, 14)); // 주말 오후
        return slots;
    }

    private static Instant atTime(LocalDate date, int hour) {
        return LocalDateTime.of(date, java.time.LocalTime.of(hour, 0))
                .atZone(KST)
                .toInstant();
    }

    // ─── OD → Route 변환 ───────────────

    private Route toRoute(OdSpec od, String preference) {
        List<Leg> legs = new ArrayList<>();
        legs.add(new Leg(
                LegType.TRANSIT, od.transitMode, od.transitMinutes, od.transitDistanceMeters,
                od.origin, od.destination,
                new TransitInfo(od.transitLineName, null, od.stationCount, od.costWon, List.of()),
                null, null
        ));
        if (od.mobility != null) {
            legs.add(new Leg(
                    LegType.BIKE, "BIKE", od.mobilityMinutes, od.mobilityDistanceMeters,
                    od.destination, od.destination,
                    null,
                    new MobilityInfo(od.mobility, "operator", null, 0, "목적지 근처",
                            od.destination.lat(), od.destination.lng(), 5, 200),
                    null
            ));
        }
        return Route.of(legs, od.routeType).withScore(0.85, /* recommended */ true);
    }
}
