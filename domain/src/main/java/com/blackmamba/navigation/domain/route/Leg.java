package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.mobility.MobilityInfo;

import com.blackmamba.navigation.domain.route.mobility.MobilityType;

import java.util.List;
import java.util.Optional;

public record Leg(
        LegType type,
        String mode,
        int durationMinutes,
        int distanceMeters,
        Location start,
        Location end,
        TransitInfo transitInfo,
        MobilityInfo mobilityInfo,
        List<Location> routeCoordinates   // 실제 도로 경로 좌표 (BIKE/KICKBOARD), null 이면 직선 표시
) {
    /**
     * 실시간 대중교통 경로를 얻지 못해 기준 경로·직선거리로 근사한 TRANSIT 구간의 mode 값.
     * 실제 조회 구간은 mode 가 "SUBWAY"/"BUS" 다.
     */
    public static final String ESTIMATED_TRANSIT_MODE = "대중교통";

    public boolean isEstimatedTransit() {
        return type == LegType.TRANSIT && ESTIMATED_TRANSIT_MODE.equals(mode);
    }

    public boolean isWalk() {
        return type == LegType.WALK;
    }

    public boolean isTransit() {
        return type == LegType.TRANSIT;
    }

    /** {@link LegType#BIKE} 구간 — 현재 매핑상 따릉이. */
    public boolean isBike() {
        return type == LegType.BIKE;
    }

    /**
     * {@link LegType#KICKBOARD} 구간 — 이름과 달리 따릉이가 아닌 모든 수단(개인 전기자전거 포함)이 여기 속한다.
     * 실제 수단은 {@link #findMobilityType()} 으로 본다.
     */
    public boolean isKickboard() {
        return type == LegType.KICKBOARD;
    }

    /** 자전거·킥보드 등 탑승 구간. */
    public boolean isRiding() {
        return isBike() || isKickboard();
    }

    /** 대중교통 구간의 수단. 대중교통 구간이 아니면 의미가 없다. */
    public TransitMode transitMode() {
        return TransitMode.from(mode);
    }

    /**
     * 탑승 구간의 실제 이동수단. {@code mobilityInfo} 가 있으면 그 값, 없으면 {@code mode} 에 실린 수단 이름으로 본다.
     * LegType 이 BIKE/KICKBOARD 둘뿐이라 수단을 가르지 못해 여러 곳이 {@code mode} 문자열을 따로 해석했다.
     */
    public Optional<MobilityType> findMobilityType() {
        if (mobilityInfo != null && mobilityInfo.mobilityType() != null) {
            return Optional.of(mobilityInfo.mobilityType());
        }
        if (!isRiding() || mode == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(MobilityType.valueOf(mode));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    public boolean hasMobilityType(MobilityType expected) {
        return findMobilityType()
                .filter(actual -> actual == expected)
                .isPresent();
    }
}
