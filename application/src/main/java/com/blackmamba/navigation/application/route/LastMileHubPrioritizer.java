package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.MobilitySearchHint;
import com.blackmamba.navigation.domain.route.MobilityType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 라스트마일 허브 후보를 "픽업 가능성" 기준으로 우선순위화한다.
 *
 * <p>따릉이는 정류소 재고가 실시간 변동하므로, baseline 정류장만으로 뽑은
 * 후보를 그대로 쓰면 "도착했는데 자전거 없음" 경험이 잦다.
 * 각 후보 주변의 가장 가까운 대여 가능 정류소(hint)를 조회해:
 * <ol>
 *   <li>hint 가 합리적 거리(1.1km) 이내인 후보를 우선</li>
 *   <li>hint 거리 오름차순 → baseline 선정 순위 순으로 정렬</li>
 *   <li>합리적 후보가 하나도 없으면 원본 순서 유지 (전부 탈락 방지)</li>
 * </ol>
 *
 * <p>원래 {@code OptimalSearchStrategy} / {@code SpecificMobilityStrategy}
 * 양쪽에 통째로 중복되어 있던 로직을 단일 컴포넌트로 추출했다.
 */
public class LastMileHubPrioritizer {

    private static final int MAX_CANDIDATE_HUBS = 5;
    private static final int MAX_HINT_PRIORITY_DISTANCE_METERS = 1_100;

    private final HubSelector hubSelector;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;

    public LastMileHubPrioritizer(HubSelector hubSelector,
                                  MobilityAvailabilityPort mobilityAvailabilityPort) {
        this.hubSelector = hubSelector;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
    }

    public Mono<List<Hub>> prioritize(List<Leg> baseLegs,
                                      Location destination,
                                      MobilityType type,
                                      MobilityConfig config) {
        List<Hub> rawHubs = hubSelector.selectLastMileHubs(baseLegs, destination, config);
        if (rawHubs.isEmpty()) {
            return Mono.just(List.of());
        }
        if (type != MobilityType.DDAREUNGI) {
            return Mono.just(rawHubs.stream().limit(MAX_CANDIDATE_HUBS).toList());
        }

        return Flux.fromIterable(rawHubs)
                .flatMap(hub -> mobilityAvailabilityPort.findNearestMobilityHint(
                                hub.location().lat(),
                                hub.location().lng(),
                                type,
                                false
                        )
                        .map(optionalHint -> hubWithPickupHint(hub, optionalHint)))
                .collectList()
                .map(this::preferPickupAccessibleHubs)
                .flatMapMany(Flux::fromIterable)
                .sort(Comparator
                        .comparing((Hub hub) -> hasReasonablePickupHint(hub) ? 0 : 1)
                        .thenComparingInt(this::pickupHintDistanceOrMax)
                        .thenComparingInt(this::selectionRankOrMax))
                .take(MAX_CANDIDATE_HUBS)
                .collectList();
    }

    private Hub hubWithPickupHint(Hub hub, Optional<MobilitySearchHint> optionalHint) {
        Map<String, String> metadata = new LinkedHashMap<>(hub.metadata());
        optionalHint.ifPresent(hint -> {
            metadata.put("pickupHintDistanceMeters", String.valueOf(hint.distanceMeters()));
            metadata.put("pickupHintStationId", hint.stationId());
            metadata.put("pickupHintStationName", hint.stationName());
            metadata.put("pickupHintAvailableCount", String.valueOf(hint.availableCount()));
        });
        return new Hub(hub.hubId(), hub.name(), hub.type(), hub.location(), hub.radiusMeters(), metadata);
    }

    private boolean hasReasonablePickupHint(Hub hub) {
        String raw = hub.metadata().get("pickupHintDistanceMeters");
        if (raw == null) return false;
        try {
            return Integer.parseInt(raw) <= MAX_HINT_PRIORITY_DISTANCE_METERS;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private int pickupHintDistanceOrMax(Hub hub) {
        String raw = hub.metadata().get("pickupHintDistanceMeters");
        if (raw == null) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private int selectionRankOrMax(Hub hub) {
        String raw = hub.metadata().get("selectionRank");
        if (raw == null) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    /** 합리적 픽업 후보가 하나라도 있으면 그것만, 없으면 전체 유지 (전멸 방지). */
    private List<Hub> preferPickupAccessibleHubs(List<Hub> hubs) {
        List<Hub> filtered = hubs.stream()
                .filter(this::hasReasonablePickupHint)
                .toList();
        return filtered.isEmpty() ? hubs : filtered;
    }
}
