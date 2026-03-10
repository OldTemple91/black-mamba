package com.blackmamba.navigation.infra.odsay;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.TransitInfo;
import com.blackmamba.navigation.infra.odsay.dto.OdsayRouteResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OdsayRouteMapper {

    public List<Leg> toLegs(OdsayRouteResponse.Path path) {
        return path.subPath().stream()
                .map(this::toLeg)
                .toList();
    }

    private Leg toLeg(OdsayRouteResponse.SubPath subPath) {
        LegType legType = switch (subPath.trafficType()) {
            case 1, 2 -> LegType.TRANSIT;
            default -> LegType.WALK;
        };

        String mode = switch (subPath.trafficType()) {
            case 1 -> "SUBWAY";
            case 2 -> "BUS";
            default -> "WALK";
        };

        List<OdsayRouteResponse.Station> stations = subPath.stations();
        Location start = extractStart(stations);
        Location end   = extractEnd(stations);

        TransitInfo transitInfo = null;
        if (legType == LegType.TRANSIT) {
            OdsayRouteResponse.Lane lane = subPath.firstLane();
            // stationCount: ODsay 직접 필드 우선, 없으면 passStopList 크기
            int stationCount = subPath.stationCount() > 0
                    ? subPath.stationCount()
                    : stations.size();
            // 경유 정류장 이름 목록 (첫 역·끝 역 포함)
            List<String> passThroughStations = stations.stream()
                    .map(OdsayRouteResponse.Station::stationName)
                    .toList();

            if (lane != null) {
                transitInfo = new TransitInfo(
                        lane.lineName(),   // 지하철: name, 버스: busNo+"번"
                        lane.busColor(),
                        stationCount,
                        passThroughStations
                );
            } else if (stationCount > 0) {
                // lane 정보 없어도 정거장 수와 경유 정보는 유지
                transitInfo = new TransitInfo(null, null, stationCount, passThroughStations);
            }
        }

        return new Leg(legType, mode, subPath.sectionTime(),
                subPath.distance(), start, end, transitInfo, null, null);
    }

    private Location extractStart(List<OdsayRouteResponse.Station> stations) {
        if (stations == null || stations.isEmpty()) return null;
        OdsayRouteResponse.Station s = stations.get(0);
        return new Location(s.stationName(), s.lat(), s.lng());
    }

    private Location extractEnd(List<OdsayRouteResponse.Station> stations) {
        if (stations == null || stations.isEmpty()) return null;
        OdsayRouteResponse.Station s = stations.getLast();
        return new Location(s.stationName(), s.lat(), s.lng());
    }
}
