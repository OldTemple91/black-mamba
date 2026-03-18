package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.application.route.MobilityConfig;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;

import java.util.List;

public interface HubSearchPort {

    List<Location> findLastMilePrimaryCandidates(List<Leg> legs, Location destination, MobilityConfig config);

    List<Location> findLastMileFallbackCandidates(Location destination, List<Leg> legs, MobilityConfig config);

    List<Location> findFirstMilePrimaryCandidates(Location origin, List<Leg> legs, MobilityConfig config);

    List<Location> findFirstMileFallbackCandidates(Location origin, List<Leg> legs, MobilityConfig config);
}
