package com.blackmamba.navigation.infra.common;

import ch.hsr.geohash.GeoHash;
import com.blackmamba.navigation.domain.location.Location;

/**
 * 좌표를 Geohash 문자열로 변환해 공간 인덱스 기반 캐시 키를 생성한다.
 * <p>
 * precision=7 기준 1 cell ≈ 150m × 150m.
 * 비슷한 위치의 요청을 같은 cell로 묶어 외부 API 호출 수를 크게 줄인다.
 * <p>
 * 한계:
 * - 격자 경계 근처의 좌표 2개는 서로 다른 키가 됨 (현실적 문제)
 * - 필요 시 {@link #forRouteWithNeighbors(Location, Location)}로 인접 격자 포함 검색 가능 (추가 구현 예정)
 */
public final class GeohashKeyGenerator {

    /** 150m × 150m 격자 - 같은 블록 수준 */
    public static final int DEFAULT_PRECISION = 7;

    private GeohashKeyGenerator() {}

    /**
     * 단일 좌표 → Geohash 문자열 (기본 precision).
     */
    public static String of(Location location) {
        return of(location, DEFAULT_PRECISION);
    }

    public static String of(Location location, int precision) {
        return GeoHash.geoHashStringWithCharacterPrecision(
                location.lat(), location.lng(), precision);
    }

    public static String of(double lat, double lng) {
        return of(lat, lng, DEFAULT_PRECISION);
    }

    public static String of(double lat, double lng, int precision) {
        return GeoHash.geoHashStringWithCharacterPrecision(lat, lng, precision);
    }

    /**
     * 경로(OD 쌍)용 키.
     * 형식: {origin_geohash}|{destination_geohash}
     */
    public static String forRoute(Location origin, Location destination) {
        return of(origin) + "|" + of(destination);
    }
}
