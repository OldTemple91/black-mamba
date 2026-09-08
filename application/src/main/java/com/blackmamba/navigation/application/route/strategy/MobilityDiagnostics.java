package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.GenerationDiagnostic;
import com.blackmamba.navigation.domain.route.MobilityInfo;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.MobilitySearchHint;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * 혼합 경로를 만들지 못했을 때 "왜 못 만들었는지" 를 사용자에게 설명할 진단을 만든다.
 * <p>
 * 후보 허브 각각에 대해 대여(pickup)/반납(dropoff) 가용성을 조회하고,
 * 전부 실패한 경우에만 대표 사유 1건을 낸다:
 * <pre>
 *   동일 정류소 대여/반납 조합만 발견   → SAME_PICKUP_DROPOFF
 *   반납만 실패                        → NO_DROPOFF   (+ 가장 가까운 반납 정류소 힌트)
 *   대여만 실패                        → NO_PICKUP    (+ 가장 가까운 대여 정류소 힌트)
 *   그 외                              → NO_VALID_COMBINATION
 * </pre>
 * 원래 {@code OptimalSearchStrategy} / {@code SpecificMobilityStrategy} 에 통째로
 * 중복되어 있던 로직을 단일 컴포넌트로 추출했다.
 */
final class MobilityDiagnostics {

    enum Phase {
        FIRST_MILE("FIRST_MILE", "퍼스트마일"),
        LAST_MILE("LAST_MILE", "라스트마일");

        private final String code;
        private final String label;

        Phase(String code, String label) {
            this.code = code;
            this.label = label;
        }
    }

    private static final String PHASE_ROUTE_COMPOSITION = "ROUTE_COMPOSITION";
    private static final String PHASE_DIRECT = "DIRECT";
    private static final String SELECTION_STRATEGY_KEY = "selectionStrategy";
    private static final String SELECTION_PRIMARY = "PRIMARY";
    private static final String SELECTION_FALLBACK = "FALLBACK_NEAREST";

    private final MobilityAvailabilityPort mobilityAvailabilityPort;

    MobilityDiagnostics(MobilityAvailabilityPort mobilityAvailabilityPort) {
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
    }

    // ── 즉시 판정 가능한 사유 ─────────────────────────────────────────────

    static GenerationDiagnostic noMixedRoute() {
        return diagnostic(PHASE_ROUTE_COMPOSITION, null, "NO_MIXED_ROUTE", 0, "혼합 경로 후보를 만들지 못했습니다.");
    }

    static GenerationDiagnostic noCandidateHub(Phase phase, MobilityType type) {
        return diagnostic(phase.code, type, "NO_CANDIDATE_HUB", 0,
                MobilityConfigs.labelFor(type) + " " + phase.label + " 후보 허브가 없습니다.");
    }

    static GenerationDiagnostic directDistanceExceeded(MobilityType type, int maxRangeMeters, double directDistanceMeters) {
        return diagnostic(PHASE_DIRECT, type, "DIRECT_DISTANCE_EXCEEDED", 0,
                MobilityConfigs.labelFor(type) + " 직접 이동 가능 거리(" + maxRangeMeters + "m)를 초과했습니다. 현재 직선거리 약 "
                        + (int) directDistanceMeters + "m");
    }

    // ── 가용성 조회가 필요한 사유 ─────────────────────────────────────────

    /**
     * 한 단계(퍼스트/라스트마일)의 후보 허브 전부가 대여/반납 불가일 때 대표 사유 1건.
     * 후보가 없거나 하나라도 성립하면 {@code Optional.empty()}.
     * <ul>
     *   <li>FIRST_MILE: 구간 = origin → hub, 대여 힌트는 origin 주변, 반납 힌트는 첫 허브 주변</li>
     *   <li>LAST_MILE : 구간 = hub → destination, 대여 힌트는 첫 허브 주변, 반납 힌트는 destination 주변</li>
     * </ul>
     */
    Mono<Optional<GenerationDiagnostic>> diagnosePhase(Phase phase, MobilityType type, List<Hub> hubs,
                                                       Location origin, Location destination) {
        if (hubs.isEmpty()) {
            return Mono.just(Optional.empty());
        }
        Location firstHub = hubs.getFirst().location();
        Location noPickupHintAt = phase == Phase.FIRST_MILE ? origin : firstHub;
        Location noDropoffHintAt = phase == Phase.FIRST_MILE ? firstHub : destination;

        return Flux.fromIterable(hubs)
                .flatMap(hub -> phase == Phase.FIRST_MILE
                        ? diagnoseSegment(origin, hub.location(), type)
                        : diagnoseSegment(hub.location(), destination, type))
                .collectList()
                .flatMap(items -> summarize(phase, type, hubs, items, noPickupHintAt, noDropoffHintAt));
    }

    private Mono<Optional<GenerationDiagnostic>> summarize(Phase phase, MobilityType type, List<Hub> hubs,
                                                           List<SegmentDiagnostic> items,
                                                           Location noPickupHintAt, Location noDropoffHintAt) {
        if (items.isEmpty() || items.stream().anyMatch(SegmentDiagnostic::isValid)) {
            return Mono.just(Optional.empty());
        }
        long noPickup = items.stream().filter(item -> item.reason() == DiagnosticReason.NO_PICKUP).count();
        long noDropoff = items.stream().filter(item -> item.reason() == DiagnosticReason.NO_DROPOFF).count();
        long sameStation = items.stream().filter(item -> item.reason() == DiagnosticReason.SAME_STATION).count();

        String prefix = MobilityConfigs.labelFor(type) + " " + phase.label + " 후보 " + candidateSummary(hubs) + "를 확인했지만 ";
        int hubCount = hubs.size();

        if (sameStation > 0) {
            return Mono.just(Optional.of(diagnostic(phase.code, type, "SAME_PICKUP_DROPOFF", hubCount,
                    prefix + "동일 정류소 대여/반납 조합만 발견되어 제외했습니다.")));
        }
        if (noDropoff > 0 && noPickup == 0) {
            return nearestHint(noDropoffHintAt, type, true)
                    .map(hint -> Optional.of(diagnostic(phase.code, type, "NO_DROPOFF", hubCount,
                            prefix + "반납 가능한 정류소를 찾지 못했습니다." + hint)));
        }
        if (noPickup > 0 && noDropoff == 0) {
            return nearestHint(noPickupHintAt, type, false)
                    .map(hint -> Optional.of(diagnostic(phase.code, type, "NO_PICKUP", hubCount,
                            prefix + "반경 내 대여 가능한 수단을 찾지 못했습니다." + hint)));
        }
        return Mono.just(Optional.of(diagnostic(phase.code, type, "NO_VALID_COMBINATION", hubCount,
                prefix + "대여/반납 가능한 수단을 찾지 못했습니다.")));
    }

    /** 대여/반납 가용성 기준의 구간 진단. 따릉이만 반납 조건이 있다. */
    private Mono<SegmentDiagnostic> diagnoseSegment(Location start, Location end, MobilityType type) {
        Mono<Optional<MobilityInfo>> pickup = mobilityAvailabilityPort.findNearbyMobility(start.lat(), start.lng(), type);

        if (type != MobilityType.DDAREUNGI) {
            return pickup.map(result -> result.isPresent()
                    ? SegmentDiagnostic.success()
                    : SegmentDiagnostic.of(DiagnosticReason.NO_PICKUP));
        }

        Mono<Optional<MobilityInfo>> dropoff = mobilityAvailabilityPort.findNearbyDropoff(end.lat(), end.lng(), type);
        return Mono.zip(pickup, dropoff)
                .map(tuple -> {
                    Optional<MobilityInfo> pickupInfo = tuple.getT1();
                    Optional<MobilityInfo> dropoffInfo = tuple.getT2();
                    if (pickupInfo.isEmpty()) {
                        return SegmentDiagnostic.of(DiagnosticReason.NO_PICKUP);
                    }
                    if (dropoffInfo.isEmpty()) {
                        return SegmentDiagnostic.of(DiagnosticReason.NO_DROPOFF);
                    }
                    MobilityInfo enriched = pickupInfo.get().withDropoffStation(
                            dropoffInfo.get().stationId(),
                            dropoffInfo.get().stationName(),
                            dropoffInfo.get().lat(),
                            dropoffInfo.get().lng()
                    );
                    if (enriched.hasSamePickupAndDropoffStation()) {
                        return SegmentDiagnostic.of(DiagnosticReason.SAME_STATION);
                    }
                    return SegmentDiagnostic.success();
                });
    }

    /** 가장 가까운 대여/반납 정류소 힌트 문구 (" " 접두). 없으면 빈 문자열. */
    private Mono<String> nearestHint(Location location, MobilityType type, boolean dropoff) {
        return Mono.defer(() -> mobilityAvailabilityPort.findNearestMobilityHint(location.lat(), location.lng(), type, dropoff))
                .map(optionalHint -> optionalHint
                        .map(hint -> " " + hint.toDiagnosticSuffix())
                        .orElse(""));
    }

    private static String candidateSummary(List<Hub> hubs) {
        long primaryCount = hubs.stream()
                .filter(hub -> SELECTION_PRIMARY.equals(hub.metadata().get(SELECTION_STRATEGY_KEY)))
                .count();
        long fallbackCount = hubs.stream()
                .filter(hub -> SELECTION_FALLBACK.equals(hub.metadata().get(SELECTION_STRATEGY_KEY)))
                .count();
        if (fallbackCount == 0) {
            return hubs.size() + "개";
        }
        return hubs.size() + "개(기본 " + primaryCount + ", fallback " + fallbackCount + ")";
    }

    private static GenerationDiagnostic diagnostic(String phase, MobilityType type, String reasonCode, int candidateCount, String message) {
        return new GenerationDiagnostic(phase, type != null ? type.name() : null, reasonCode, candidateCount, message);
    }

    private record SegmentDiagnostic(boolean valid, DiagnosticReason reason) {
        private boolean isValid() {
            return valid;
        }

        private static SegmentDiagnostic success() {
            return new SegmentDiagnostic(true, DiagnosticReason.VALID);
        }

        private static SegmentDiagnostic of(DiagnosticReason reason) {
            return new SegmentDiagnostic(false, reason);
        }
    }

    private enum DiagnosticReason {
        VALID,
        NO_PICKUP,
        NO_DROPOFF,
        SAME_STATION
    }
}
