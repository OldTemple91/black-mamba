package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.TransitInfo;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 혼합 경로의 대중교통 구간 Leg 를 만든다.
 * <p>
 * 부분 구간(예: 환승점→목적지)에 대한 실제 대중교통 경로가 비어 있을 때,
 * 전체 기준 경로(baseLegs)의 노선명·색상·정거장 수를 비율로 근사해 채운다.
 */
final class TransitLegFactory {

    private static final String TRANSIT_MODE = Leg.ESTIMATED_TRANSIT_MODE;
    private static final int MAX_LINE_NAMES = 3;
    private static final int MIN_STATIONS = 2;

    private TransitLegFactory() {
    }

    /** 노선 정보 없는 단순 대중교통 Leg. */
    static Leg plain(int minutes, Location from, Location to) {
        return new Leg(LegType.TRANSIT, TRANSIT_MODE, minutes, 0, from, to, null, null, null);
    }

    /**
     * baseLegs 에서 노선명·색상·요금을 추출해 TransitInfo 를 채운 Leg.
     * baseLegs 가 비어있거나 TRANSIT 정보가 없으면 transitInfo=null (요금은 추정 정책이 기본요금을 매긴다).
     * 요금을 0으로 두면 조회에 실패한 후보가 비용 점수 만점을 받는다 — 기준 경로의 요금을 상한 근사로 쓴다.
     */
    static Leg approximate(int minutes, Location from, Location to, List<Leg> baseLegs) {
        List<Leg> transitLegs = baseLegs.stream()
                .filter(l -> l.type() == LegType.TRANSIT && l.transitInfo() != null)
                .toList();

        if (transitLegs.isEmpty()) {
            return plain(minutes, from, to);
        }

        // 노선명: 최대 3개 중복 제거 후 합산 (예: "2호선, 64번")
        String lineName = transitLegs.stream()
                .map(l -> l.transitInfo().lineName())
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .limit(MAX_LINE_NAMES)
                .collect(Collectors.joining(", "));

        // 노선 색상: 첫 번째 TRANSIT leg 색상 사용
        String lineColor = transitLegs.stream()
                .map(l -> l.transitInfo().lineColor())
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElse(null);

        // 정거장 수: 전체 기준 소요시간 비율로 근사
        int totalBaseMinutes = baseLegs.stream().mapToInt(Leg::durationMinutes).sum();
        int totalStations = transitLegs.stream().mapToInt(l -> l.transitInfo().stationCount()).sum();
        int approxStations = totalBaseMinutes > 0
                ? Math.max(MIN_STATIONS, (int) Math.round((double) minutes / totalBaseMinutes * totalStations))
                : Math.max(MIN_STATIONS, totalStations / 2);

        // 경유 정류장: base 경로의 passThroughStations 합산 (근사 표시용)
        List<Location> passThroughStations = transitLegs.stream()
                .filter(l -> l.transitInfo().passThroughStations() != null)
                .flatMap(l -> l.transitInfo().passThroughStations().stream())
                .toList();

        int baseFareWon = transitLegs.stream().mapToInt(l -> l.transitInfo().fareWon()).sum();

        TransitInfo transitInfo = lineName.isBlank() ? null
                : new TransitInfo(lineName, lineColor, approxStations, baseFareWon, passThroughStations);

        return new Leg(LegType.TRANSIT, TRANSIT_MODE, minutes, 0, from, to, transitInfo, null, null);
    }

    /** 대중교통 소요 시간 추정 (직선거리 × 1.4 우회계수 ÷ 25 km/h, 최소 5분) */
    static int haversineTransitMinutes(Location a, Location b) {
        double distKm = GeoDistance.kilometers(a, b);
        return Math.max((int) Math.ceil(distKm * 1.4 / 25.0 * 60), 5);
    }
}
