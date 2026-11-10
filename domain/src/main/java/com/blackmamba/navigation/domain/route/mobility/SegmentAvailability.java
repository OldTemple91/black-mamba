package com.blackmamba.navigation.domain.route.mobility;

import java.util.Optional;

/**
 * 이동수단 구간 하나가 성립하는가 — 성립하면 그 수단 정보, 아니면 왜 안 되는지.
 * <p>
 * "대여 가능한 정류소가 있고, 반납 정류소가 있고, 둘이 서로 다르다" 는 혼합 경로의 핵심 규칙인데,
 * 예전엔 경로를 만드는 쪽(infra 어댑터)과 못 만든 이유를 설명하는 쪽(application 진단)이 따로 구현했다.
 * 규칙을 여기 한 곳에 두고 양쪽이 같은 판정을 쓴다 — "만들지 못한 이유" 가 실제 생성 로직과 어긋날 수 없다.
 */
public sealed interface SegmentAvailability {

    /** 구간 성립 — 반납 정류소가 필요한 수단이면 {@code info} 에 반납 정류소가 채워져 있다. */
    record Available(MobilityInfo info) implements SegmentAvailability {}

    /** 출발 지점 반경 안에 대여 가능한 수단이 없다. */
    record NoPickup() implements SegmentAvailability {}

    /** 도착 지점 반경 안에 반납할 정류소가 없다. */
    record NoDropoff() implements SegmentAvailability {}

    /** 대여와 반납이 같은 정류소 — 이동이 아니다. */
    record SamePickupDropoff() implements SegmentAvailability {}

    /**
     * @param pickup  출발 지점 주변 조회 결과
     * @param dropoff 도착 지점 주변 반납 정류소 조회 결과 — 반납 정류소가 필요 없는 수단이면 보지 않는다
     */
    static SegmentAvailability of(MobilityType type, Optional<MobilityInfo> pickup, Optional<MobilityInfo> dropoff) {
        if (pickup.isEmpty()) {
            return new NoPickup();
        }
        if (!type.requiresDropoffStation()) {
            return new Available(pickup.get());
        }
        if (dropoff.isEmpty()) {
            return new NoDropoff();
        }
        MobilityInfo station = dropoff.get();
        MobilityInfo withDropoff = pickup.get().withDropoffStation(station.stationId(), station.stationName(), station.lat(), station.lng());
        if (withDropoff.hasSamePickupAndDropoffStation()) {
            return new SamePickupDropoff();
        }
        return new Available(withDropoff);
    }

    /** 성립한 구간의 수단 정보. 성립하지 않으면 비어 있다. */
    default Optional<MobilityInfo> findInfo() {
        if (this instanceof Available(MobilityInfo info)) {
            return Optional.of(info);
        }
        return Optional.empty();
    }
}
