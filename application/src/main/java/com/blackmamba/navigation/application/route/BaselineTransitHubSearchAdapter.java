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
        );
    }

    @Override
    public List<Location> findLastMileFallbackCandidates(Location destination, List<Leg> legs, MobilityConfig config) {
        return candidatePointSelector.selectLastMileFallback(destination, legs, config);
    }

    @Override
    public List<Location> findFirstMilePrimaryCandidates(Location origin, List<Leg> legs, MobilityConfig config) {
        return candidatePointSelector.selectFirstMile(origin, legs, config);
    }

    @Override
    public List<Location> findFirstMileFallbackCandidates(Location origin, List<Leg> legs, MobilityConfig config) {
        return candidatePointSelector.selectFirstMileFallback(origin, legs, config);
    }
}
