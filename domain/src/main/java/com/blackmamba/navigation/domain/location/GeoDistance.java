package com.blackmamba.navigation.domain.location;

/**
 * Haversine 거리 계산 — 프로젝트 전역의 단일 진실 공급원.
 * <p>
 * 도입 전에는 같은 공식이 12곳에 복사되어 있었고 지구 반지름 단위도
 * 미터(6_371_000)와 킬로미터(6371.0)가 혼재했다. 정밀도 개선이나 버그 수정 시
 * 전부 찾아 고쳐야 하는 구조라 한 곳으로 통합한다.
 * <p>
 * 순수 수학 — 외부 의존성 없음 (domain 배치 근거).
 */
public final class GeoDistance {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private GeoDistance() {
    }

    /** 두 좌표 사이 직선(대원) 거리 — 미터. */
    public static double meters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    public static double meters(Location a, Location b) {
        return meters(a.lat(), a.lng(), b.lat(), b.lng());
    }

    /** 두 좌표 사이 직선(대원) 거리 — 킬로미터. */
    public static double kilometers(double lat1, double lng1, double lat2, double lng2) {
        return meters(lat1, lng1, lat2, lng2) / 1_000.0;
    }

    public static double kilometers(Location a, Location b) {
        return meters(a, b) / 1_000.0;
    }
}
