package com.blackmamba.navigation.domain.hub;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.mobility.MobilitySearchHint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 환승 거점 후보.
 * <p>
 * 우선순위 판단에 쓰는 값(선택 순위·대여 힌트)은 타입 필드로 둔다. 예전엔 {@code metadata} 문자열 맵에
 * 숫자를 문자열로 넣었다가 정렬할 때 다시 파싱했는데, 키 오타 하나면 정렬이 조용히 무력해지고 테스트도 깨지지 않았다.
 * {@code metadata} 는 표시용 부가 정보만 담고, 응답에 내보낼 때는 {@link #displayMetadata()} 가 타입 필드를 합친다.
 *
 * @param selection  후보로 뽑힌 경위 (직접 만든 허브는 null)
 * @param pickupHint 허브 주변 최근접 대여 정류소 (조회하지 않았거나 없으면 null)
 */
public record Hub(
        String hubId,
        String name,
        HubType type,
        Location location,
        int radiusMeters,
        HubSelection selection,
        MobilitySearchHint pickupHint,
        Map<String, String> metadata
) {
    public static final String META_SELECTION_STRATEGY = "selectionStrategy";
    public static final String META_SELECTION_RANK = "selectionRank";
    public static final String META_PICKUP_HINT_DISTANCE_METERS = "pickupHintDistanceMeters";
    public static final String META_PICKUP_HINT_STATION_ID = "pickupHintStationId";
    public static final String META_PICKUP_HINT_STATION_NAME = "pickupHintStationName";
    public static final String META_PICKUP_HINT_AVAILABLE_COUNT = "pickupHintAvailableCount";

    public Hub {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Hub(String hubId, String name, HubType type, Location location, int radiusMeters, Map<String, String> metadata) {
        this(hubId, name, type, location, radiusMeters, null, null, metadata);
    }

    public Hub withPickupHint(MobilitySearchHint hint) {
        return new Hub(hubId, name, type, location, radiusMeters, selection, hint, metadata);
    }

    public boolean hasPickupHintWithin(int maxDistanceMeters) {
        return pickupHint != null && pickupHint.distanceMeters() <= maxDistanceMeters;
    }

    /** 대여 힌트까지의 거리. 힌트가 없으면 정렬에서 맨 뒤로 가도록 최댓값. */
    public int pickupHintDistanceOrMax() {
        if (pickupHint == null) {
            return Integer.MAX_VALUE;
        }
        return pickupHint.distanceMeters();
    }

    public int selectionRankOrMax() {
        if (selection == null) {
            return Integer.MAX_VALUE;
        }
        return selection.rank();
    }

    public boolean isPrimarySelection() {
        return selection != null && selection.isPrimary();
    }

    public boolean isFallbackSelection() {
        return selection != null && selection.isFallback();
    }

    /** 응답·평가에 내보내는 문자열 맵 — 표시용 metadata 에 타입 필드를 합친다. 키는 이 클래스의 META_* 상수. */
    public Map<String, String> displayMetadata() {
        Map<String, String> display = new LinkedHashMap<>(metadata);
        if (selection != null) {
            display.put(META_SELECTION_STRATEGY, selection.strategy().name());
            display.put(META_SELECTION_RANK, String.valueOf(selection.rank()));
        }
        if (pickupHint != null) {
            display.put(META_PICKUP_HINT_DISTANCE_METERS, String.valueOf(pickupHint.distanceMeters()));
            display.put(META_PICKUP_HINT_STATION_ID, pickupHint.stationId());
            display.put(META_PICKUP_HINT_STATION_NAME, pickupHint.stationName());
            display.put(META_PICKUP_HINT_AVAILABLE_COUNT, String.valueOf(pickupHint.availableCount()));
        }
        return display;
    }
}
