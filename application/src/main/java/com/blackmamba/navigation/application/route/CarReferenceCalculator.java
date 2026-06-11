package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.route.CarReference;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteComparison;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 같은 출발/도착지를 자가용으로 이동했을 때의 기준값을 추정하고,
 * 구체 경로(Route)와의 비교 결과를 생성한다.
 *
 * <p>목적: MaaS 서비스가 "자가용 대체"를 설득하려면 사용자가 비교할 기준이 필요하다.
 * 단순 "38분 / 1,450원"보다 "자가용보다 +10분 더 걸리지만 -6,750원 절약"이
 * 훨씬 강한 설득 메시지이다.
 *
 * <h3>계산 가정 (도심 주행 평균, 한국 기준)</h3>
 * <ul>
 *   <li>우회 계수: 직선거리 × 1.3 (실 도로 거리)</li>
 *   <li>연비: 12 km/L (승용차 평균)</li>
 *   <li>휘발유 가격: 1,700 원/L</li>
 *   <li>주차비: 3,000 원 (기본 1시간)</li>
 *   <li>톨게이트: km당 100원 (30km 초과 시)</li>
 *   <li>CO₂ 배출: 171 g/km (환경부 공식 평균)</li>
 *   <li>속도: 도심 25 km/h, 외곽 40 km/h, 고속 70 km/h</li>
 * </ul>
 * 향후 application.yml 에 상수로 빼서 지역별/차종별 조정 가능하도록 확장 여지.
 */
@Component
public class CarReferenceCalculator {

    // 주행 환경 구분 (km)
    private static final double URBAN_KM_MAX = 30.0;
    private static final double SUBURBAN_KM_MAX = 80.0;

    // 속도 (km/h)
    private static final double URBAN_KMH = 25.0;
    private static final double SUBURBAN_KMH = 40.0;
    private static final double HIGHWAY_KMH = 70.0;

    // 비용 (원)
    private static final double FUEL_EFFICIENCY_KM_PER_L = 12.0;
    private static final int FUEL_PRICE_PER_L = 1_700;
    private static final int PARKING_COST = 3_000;
    private static final int TOLL_PER_KM = 100;
    private static final double TOLL_THRESHOLD_KM = 30.0;

    // 기타 상수
    private static final double DETOUR_FACTOR = 1.3;

    private final CarbonFootprintCalculator carbonFootprintCalculator;

    public CarReferenceCalculator(CarbonFootprintCalculator carbonFootprintCalculator) {
        this.carbonFootprintCalculator = carbonFootprintCalculator;
    }

    /**
     * 출발/도착 좌표로부터 자가용 기준값 계산.
     * CO₂ 계수는 {@link CarbonFootprintCalculator#CAR_G_PER_KM} 에서 공유 (171 g/km).
     */
    public CarReference estimate(Location origin, Location destination) {
        double distKm = haversineKm(origin, destination) * DETOUR_FACTOR;

        int minutes = estimateMinutes(distKm);
        int fuel = (int) Math.round(distKm / FUEL_EFFICIENCY_KM_PER_L * FUEL_PRICE_PER_L);
        int parking = PARKING_COST;
        int toll = distKm > TOLL_THRESHOLD_KM
                ? (int) Math.round((distKm - TOLL_THRESHOLD_KM) * TOLL_PER_KM)
                : 0;

        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("fuel", fuel);
        breakdown.put("parking", parking);
        if (toll > 0) breakdown.put("toll", toll);

        int totalCost = fuel + parking + toll;
        double co2 = carbonFootprintCalculator.forCarDistance(distKm);

        return new CarReference(minutes, totalCost, co2, breakdown);
    }

    /**
     * 주어진 Route가 자가용 대비 얼마나 나은지 비교.
     * Route 의 CO₂ 는 {@link CarbonFootprintCalculator} 로 이동수단별 정밀 계산.
     */
    public RouteComparison compareWithRoute(Route route, Location origin, Location destination) {
        CarReference car = estimate(origin, destination);

        int timeDiff = route.totalMinutes() - car.estimatedMinutes();
        int costSaved = car.estimatedCostWon() - route.totalCostWon();
        double co2Reduced = car.estimatedCo2Grams() - carbonFootprintCalculator.forRoute(route);

        String narrative = buildNarrative(timeDiff, costSaved, co2Reduced);

        return new RouteComparison(car, timeDiff, costSaved, co2Reduced, narrative);
    }

    private int estimateMinutes(double distKm) {
        double speedKmh;
        if (distKm <= URBAN_KM_MAX) {
            speedKmh = URBAN_KMH;
        } else if (distKm <= SUBURBAN_KM_MAX) {
            speedKmh = SUBURBAN_KMH;
        } else {
            speedKmh = HIGHWAY_KMH;
        }
        return Math.max(1, (int) Math.ceil(distKm / speedKmh * 60));
    }

    private String buildNarrative(int timeDiffMinutes, int costSavedWon, double co2ReducedGrams) {
        StringBuilder sb = new StringBuilder();

        if (timeDiffMinutes > 0) {
            sb.append(String.format("자가용보다 %d분 더 걸리지만 ", timeDiffMinutes));
        } else if (timeDiffMinutes < 0) {
            sb.append(String.format("자가용보다 %d분 빠르며 ", Math.abs(timeDiffMinutes)));
        } else {
            sb.append("자가용과 같은 시간에 도착하며 ");
        }

        if (costSavedWon > 0) {
            sb.append(String.format("%,d원 절약", costSavedWon));
        } else if (costSavedWon < 0) {
            sb.append(String.format("%,d원 더 듭니다", Math.abs(costSavedWon)));
        } else {
            sb.append("동일 비용");
        }

        if (co2ReducedGrams > 100) {
            if (co2ReducedGrams >= 1000) {
                sb.append(String.format(", 탄소 %.1fkg 감소", co2ReducedGrams / 1000.0));
            } else {
                sb.append(String.format(", 탄소 %.0fg 감소", co2ReducedGrams));
            }
        }
        sb.append(".");
        return sb.toString();
    }

    private double haversineKm(Location a, Location b) {
        return GeoDistance.kilometers(a, b);
    }
}
