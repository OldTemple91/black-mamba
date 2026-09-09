package com.blackmamba.navigation.domain.route;


/**
 * C-2: 경로별 탄소 배출량 계산기 (순수 계산 — 부수효과 없음).
 *
 * <p>"자가용 대비" 가 아니라 <b>경로 자체의 독립 CO₂ 값</b> 을 산출한다.
 * 프론트엔드는 이 값을 이용해 친환경 뱃지 / Carbon Budget 레이아웃을 만든다.
 * 메트릭 기록은 {@link CarbonMetrics} 가 담당한다 — 계산기 안에서 기록하면
 * 한 경로에 대해 {@code forRoute} 가 호출될 때마다(친환경 판정, 자가용 비교 등)
 * 같은 값이 여러 번 기록되어 히스토그램이 부풀려진다.
 *
 * <h3>배출 계수 (g CO₂ / km · 1인 기준)</h3>
 * <ul>
 *   <li><b>지하철</b>  41 g/km — 한국철도 '2023 지속가능경영보고서' 1인km당 탄소강도</li>
 *   <li><b>버스</b>    68 g/km — 서울시 버스운송사업 평균 (디젤/CNG 혼합)</li>
 *   <li><b>공유 킥보드</b> 22 g/km — 배터리 전력 + 회수차량 왕복 포함 LCA (Hollingsworth 2019)</li>
 *   <li><b>개인 킥보드</b> 14 g/km — 회수 물류 제외, 배터리 전력만</li>
 *   <li><b>개인 전기자전거</b> 10 g/km — 전력 소비 극소</li>
 *   <li><b>따릉이/도보/일반 자전거</b> 0 g/km</li>
 *   <li><b>자가용(비교용)</b> 171 g/km — 환경부 '2023 국가 온실가스 인벤토리' 승용 휘발유 평균</li>
 * </ul>
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li>CarReferenceCalculator 에 녹아있던 CO₂ 로직을 독립 컴포넌트로 추출.</li>
 *   <li>이동수단별 정밀 계수로 분리 — "대중교통 = 버스 68" 단순화 오류 제거.</li>
 *   <li>Leg.mode() 의 "SUBWAY"/"BUS" 문자열로 지하철/버스 구분 (ODsay Mapper 가 설정).</li>
 * </ul>
 */
public class CarbonFootprintCalculator {

    // === 대중교통 (1인 환산) ===
    private static final double SUBWAY_G_PER_KM = 41.0;
    private static final double BUS_G_PER_KM = 68.0;
    private static final double TRANSIT_DEFAULT_G_PER_KM = 55.0;  // mode 미상 시 평균

    // === 이동수단 (1인 환산) ===
    private static final double SHARED_KICKBOARD_G_PER_KM = 22.0;
    private static final double PERSONAL_KICKBOARD_G_PER_KM = 14.0;
    private static final double EBIKE_G_PER_KM = 10.0;
    private static final double BIKE_G_PER_KM = 0.0;  // 따릉이 / 일반 자전거
    private static final double WALK_G_PER_KM = 0.0;

    // === 자가용 (비교용) ===
    public static final double CAR_G_PER_KM = 171.0;

    /** 친환경 판정 임계 — km 당 배출 강도가 이 값 미만이면 eco. */
    private static final double ECO_INTENSITY_G_PER_KM = 20.0;

    // === 모드 문자열 상수 (ODsay Mapper 와 일치) ===
    private static final String MODE_SUBWAY = "SUBWAY";
    private static final String MODE_BUS = "BUS";

    /**
     * Route 전체의 CO₂ 배출량 (g) 계산. 반환값은 음수가 될 수 없다.
     */
    public double forRoute(Route route) {
        if (route == null || route.legs() == null) return 0.0;
        return route.legs().stream().mapToDouble(this::forLeg).sum();
    }

    /**
     * 개별 Leg CO₂. 거리가 0 이하이면 0 반환.
     */
    public double forLeg(Leg leg) {
        if (leg == null || leg.distanceMeters() <= 0) return 0.0;
        double km = leg.distanceMeters() / 1_000.0;
        return km * coefficientFor(leg);
    }

    /**
     * 직선거리(km) × 자가용 계수.
     */
    public double forCarDistance(double km) {
        return Math.max(0, km) * CAR_G_PER_KM;
    }

    /**
     * 이동수단이 "친환경" 인지 (자전거/도보/전기자전거 등 < 20 g/km).
     */
    public boolean isEcoRoute(Route route) {
        if (route == null) return false;
        double totalMeters = route.legs().stream().mapToInt(Leg::distanceMeters).sum();
        if (totalMeters <= 0) return false;
        double grams = forRoute(route);
        double intensityPerKm = grams / (totalMeters / 1_000.0);
        return intensityPerKm < ECO_INTENSITY_G_PER_KM;
    }

    private double coefficientFor(Leg leg) {
        return switch (leg.type()) {
            case WALK -> WALK_G_PER_KM;
            case BIKE -> BIKE_G_PER_KM;   // 따릉이 / 일반 자전거
            case KICKBOARD -> mobilityCoefficientByMode(leg);
            case TRANSIT -> transitCoefficient(leg);
        };
    }

    private double transitCoefficient(Leg leg) {
        String mode = leg.mode();
        if (MODE_SUBWAY.equalsIgnoreCase(mode)) return SUBWAY_G_PER_KM;
        if (MODE_BUS.equalsIgnoreCase(mode)) return BUS_G_PER_KM;
        return TRANSIT_DEFAULT_G_PER_KM;
    }

    /**
     * LegType.KICKBOARD 에는 실제 킥보드 외에 PERSONAL_EBIKE 까지 포함된다
     * (MobilitySegmentBuilder 매핑 상). leg.mode() 로 세분해 정확 계수 적용.
     */
    private double mobilityCoefficientByMode(Leg leg) {
        String mode = leg.mode();
        if (mode == null) return PERSONAL_KICKBOARD_G_PER_KM;
        return switch (mode) {
            case "PERSONAL_EBIKE"     -> EBIKE_G_PER_KM;
            case "PERSONAL_KICKBOARD" -> PERSONAL_KICKBOARD_G_PER_KM;
            case "KICKBOARD_SHARED"   -> SHARED_KICKBOARD_G_PER_KM;
            default                   -> PERSONAL_KICKBOARD_G_PER_KM;
        };
    }
}
