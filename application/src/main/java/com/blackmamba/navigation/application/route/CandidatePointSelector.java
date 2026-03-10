package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.MobilityType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 대중교통 경로의 중간 30~80% 구간 정류장을 후보 환승 지점으로 선택.
 * ODsay는 중간 정류장 좌표를 별도 제공하지 않으므로,
 * TransitInfo.stationCount를 기반으로 start→end 사이 좌표를 선형 보간하여 생성.
 */
@Component
public class CandidatePointSelector {

    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final double MIN_RATIO = 0.3;
    private static final double MAX_RATIO = 0.8;

    public List<Location> select(List<Leg> legs, MobilityConfig config) {
        List<Location> allStops = extractTransitStops(legs);
        if (allStops.size() < 3) return List.of();

        int from = (int) (allStops.size() * MIN_RATIO);
        int to   = (int) (allStops.size() * MAX_RATIO);

        return allStops.subList(from, to);
    }

    public List<Location> filterByMobilityRange(List<Location> candidates,
                                                 Location destination,
                                                 MobilityType mobilityType) {
        int maxRange = mobilityType == MobilityType.KICKBOARD_SHARED ? 5000 : 10000;

        return candidates.stream()
                .filter(stop -> distanceMeters(
                        stop.lat(), stop.lng(),
                        destination.lat(), destination.lng()) <= maxRange)
                .toList();
    }

    /**
     * 퍼스트마일용: 경로의 첫 0~30% 구간 정류장 중 출발지에서 이동수단 범위 이내인 것 반환.
     */
    public List<Location> selectFirstMile(Location origin, List<Leg> legs, MobilityConfig config) {
        List<Location> allStops = extractTransitStops(legs);
        if (allStops.isEmpty()) return List.of();

        int to = Math.max(1, (int) (allStops.size() * 0.3));
        List<Location> firstSegment = allStops.subList(0, Math.min(to, allStops.size()));

        return firstSegment.stream()
                .filter(stop -> distanceMeters(
                        origin.lat(), origin.lng(),
                        stop.lat(), stop.lng()) <= config.maxRangeMeters())
                .toList();
    }

    /**
     * TRANSIT Leg에서 정류장 목록을 추출.
     * transitInfo.stationCount()를 이용해 start→end 사이를 선형 보간하여 중간 정류장 좌표를 생성.
     * transitInfo가 없으면 start/end 2점만 사용.
     */
    private List<Location> extractTransitStops(List<Leg> legs) {
        List<Location> stops = new ArrayList<>();
        for (Leg leg : legs) {
            if (leg.type() != LegType.TRANSIT) continue;
            if (leg.start() == null || leg.end() == null) continue;

            int count = (leg.transitInfo() != null && leg.transitInfo().stationCount() > 1)
                    ? leg.transitInfo().stationCount()
                    : 2;

            double latStep = (leg.end().lat() - leg.start().lat()) / (count - 1);
            double lngStep = (leg.end().lng() - leg.start().lng()) / (count - 1);

            List<String> names = (leg.transitInfo() != null && leg.transitInfo().passThroughStations() != null)
                    ? leg.transitInfo().passThroughStations()
                    : List.of();

            for (int i = 0; i < count; i++) {
                double lat = leg.start().lat() + i * latStep;
                double lng = leg.start().lng() + i * lngStep;
                String name = i < names.size() ? names.get(i) : leg.start().name();
                stops.add(new Location(name, lat, lng));
            }
        }
        return stops;
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
