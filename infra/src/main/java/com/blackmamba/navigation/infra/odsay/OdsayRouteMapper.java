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

        Location start = extractStart(subPath);
        Location end = extractEnd(subPath);

        TransitInfo transitInfo = null;
        if (legType == LegType.TRANSIT && subPath.lane() != null) {
            transitInfo = new TransitInfo(
                    subPath.lane().name(),
                    subPath.lane().busColor(),
                    subPath.passStopList().size()
            );
        }

        return new Leg(legType, mode, subPath.sectionTime(),
                subPath.distance(), start, end, transitInfo, null);
    }

    private Location extractStart(OdsayRouteResponse.SubPath subPath) {
        List<OdsayRouteResponse.Station> stations = subPath.passStopList();
        if (stations != null && !stations.isEmpty()) {
            OdsayRouteResponse.Station s = stations.get(0);
            return new Location(s.stationName(), s.lat(), s.lng());
        }
        return new Location("출발", 0, 0);
    }

    private Location extractEnd(OdsayRouteResponse.SubPath subPath) {
        List<OdsayRouteResponse.Station> stations = subPath.passStopList();
        if (stations != null && !stations.isEmpty()) {
            OdsayRouteResponse.Station s = stations.get(stations.size() - 1);
            return new Location(s.stationName(), s.lat(), s.lng());
        }
        return new Location("도착", 0, 0);
    }
}
