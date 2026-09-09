package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.route.Leg;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * baseline 대중교통 경로를 따라 허브 후보를 찾는 검색.
 * <p>
 * 허브 "검색"(여기)과 허브 "선택/의미 부여"({@link HubSelector}) 책임을 나눈다.
 * 외부 시스템을 호출하지 않는 순수 application 로직이므로 Port 가 아니다 —
 * 예전엔 {@code HubSearchPort} 인터페이스 뒤에 있었지만 구현체가 이 클래스 하나뿐이고
 * infra 에 대안 구현이 없어, 이름만 "Adapter" 인 간접층을 걷어냈다.
 */
@Component
public class BaselineTransitHubSearch {

    private final CandidatePointSelector candidatePointSelector;

    public BaselineTransitHubSearch(CandidatePointSelector candidatePointSelector) {
        this.candidatePointSelector = candidatePointSelector;
    }

    public List<Location> findLastMilePrimaryCandidates(List<Leg> legs, Location destination, MobilityConfig config) {
        return candidatePointSelector.filterByMobilityFeasibility(
                candidatePointSelector.select(legs, config),
                destination,
                config
        ).stream()
                .sorted((a, b) -> Double.compare(distanceMeters(a, destination), distanceMeters(b, destination)))
                .toList();
    }

    public List<Location> findLastMileFallbackCandidates(Location destination, List<Leg> legs, MobilityConfig config) {
        return candidatePointSelector.selectLastMileFallback(destination, legs, config);
    }

    public List<Location> findFirstMilePrimaryCandidates(Location origin, List<Leg> legs, MobilityConfig config) {
        return candidatePointSelector.selectFirstMile(origin, legs, config).stream()
                .sorted((a, b) -> Double.compare(distanceMeters(origin, a), distanceMeters(origin, b)))
                .toList();
    }

    public List<Location> findFirstMileFallbackCandidates(Location origin, List<Leg> legs, MobilityConfig config) {
        return candidatePointSelector.selectFirstMileFallback(origin, legs, config);
    }

    private double distanceMeters(Location from, Location to) {
        return GeoDistance.meters(from, to);
    }
}
