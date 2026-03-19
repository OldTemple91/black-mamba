package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.HubSearchPort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * baseline 대중교통 경로를 따라 허브 후보를 찾는 기본 검색 구현.
 * 허브 "검색" 책임과 허브 "선택/의미 부여" 책임을 분리하기 위한 첫 단계다.
 */
@Component
public class BaselineTransitHubSearchAdapter implements HubSearchPort {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final CandidatePointSelector candidatePointSelector;

    public BaselineTransitHubSearchAdapter(CandidatePointSelector candidatePointSelector) {
        this.candidatePointSelector = candidatePointSelector;
    }

    @Override
    public List<Location> findLastMilePrimaryCandidates(List<Leg> legs, Location destination, MobilityConfig config) {
        return candidatePointSelector.filterByMobilityFeasibility(
                candidatePointSelector.select(legs, config),
                destination,
                config
        ).stream()
                .sorted((a, b) -> Double.compare(distanceMeters(a, destination), distanceMeters(b, destination)))
                .toList();
    }

    @Override
    public List<Location> findLastMileFallbackCandidates(Location destination, List<Leg> legs, MobilityConfig config) {
        return candidatePointSelector.selectLastMileFallback(destination, legs, config);
    }

    @Override
    public List<Location> findFirstMilePrimaryCandidates(Location origin, List<Leg> legs, MobilityConfig config) {
        return candidatePointSelector.selectFirstMile(origin, legs, config).stream()
                .sorted((a, b) -> Double.compare(distanceMeters(origin, a), distanceMeters(origin, b)))
                .toList();
    }

    @Override
    public List<Location> findFirstMileFallbackCandidates(Location origin, List<Leg> legs, MobilityConfig config) {
        return candidatePointSelector.selectFirstMileFallback(origin, legs, config);
    }

    private double distanceMeters(Location from, Location to) {
        double dLat = Math.toRadians(to.lat() - from.lat());
        double dLng = Math.toRadians(to.lng() - from.lng());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(from.lat())) * Math.cos(Math.toRadians(to.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
