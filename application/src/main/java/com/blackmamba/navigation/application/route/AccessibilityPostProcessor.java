package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.TransitInfo;
import com.blackmamba.navigation.domain.location.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 접근성(Accessibility) 요청을 반영해 경로 결과를 후처리.
 * <p>
 * 원칙: 기존 라우팅 파이프라인에 침투하지 않고 <b>최종 결과 집합을 필터/재계산</b>한다.
 * - 라우팅 알고리즘 복잡도 증가 없음
 * - Accessibility 옵션 추가/제거가 다른 로직에 영향 없음
 *
 * <h3>처리 내용</h3>
 * <ol>
 *   <li>휠체어 접근성: 엘리베이터 없는 환승역이 포함된 경로 제거</li>
 *   <li>보행 속도 조정: WALK leg의 durationMinutes를 사용자 속도로 재계산 → 총시간 업데이트</li>
 * </ol>
 */
@Component
public class AccessibilityPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(AccessibilityPostProcessor.class);
    private static final double DEFAULT_WALK_KMH = 4.5;

    private final AccessibilityStationRegistry stationRegistry;

    public AccessibilityPostProcessor(AccessibilityStationRegistry stationRegistry) {
        this.stationRegistry = stationRegistry;
    }

    public List<Route> apply(List<Route> routes, AccessibilityContext ctx) {
        if (!ctx.hasAnyConstraint()) {
            return routes;
        }

        List<Route> result = new ArrayList<>();
        for (Route route : routes) {
            if (ctx.wheelchairAccessible() && containsInaccessibleStation(route)) {
                log.info("[접근성] 엘리베이터 미지원 역 포함 경로 제외: routeId={}", route.routeId());
                continue;
            }
            Route adjusted = ctx.walkingSpeedKmh() != null
                    ? recomputeWalkingDuration(route, ctx.walkingSpeedKmh())
                    : route;
            result.add(adjusted);
        }
        return result;
    }

    private boolean containsInaccessibleStation(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.TRANSIT)
                .anyMatch(leg -> !isStationAccessible(leg));
    }

    private boolean isStationAccessible(Leg leg) {
        // start/end 역 이름과 경유 정류장 모두 검증
        if (leg.start() != null && !stationRegistry.isWheelchairAccessible(leg.start().name())) return false;
        if (leg.end() != null && !stationRegistry.isWheelchairAccessible(leg.end().name())) return false;

        TransitInfo info = leg.transitInfo();
        if (info != null && info.passThroughStations() != null) {
            return info.passThroughStations().stream()
                    .map(Location::name)
                    .allMatch(stationRegistry::isWheelchairAccessible);
        }
        return true;
    }

    /**
     * WALK leg의 duration을 사용자 보행 속도로 재계산하고 총 시간 업데이트.
     * distanceMeters 기준 → 새 속도로 분 환산 후 leg/total 반영.
     */
    private Route recomputeWalkingDuration(Route route, double walkingSpeedKmh) {
        if (walkingSpeedKmh <= 0) return route;
        double speedRatio = DEFAULT_WALK_KMH / walkingSpeedKmh;   // 3km/h 가정 시 ratio=1.5 → 시간 1.5배

        List<Leg> recomputedLegs = new ArrayList<>();
        int totalDelta = 0;

        for (Leg leg : route.legs()) {
            if (leg.type() == LegType.WALK && leg.distanceMeters() > 0) {
                int newDuration = (int) Math.ceil(leg.durationMinutes() * speedRatio);
                totalDelta += newDuration - leg.durationMinutes();
                recomputedLegs.add(new Leg(
                        leg.type(), leg.mode(), newDuration, leg.distanceMeters(),
                        leg.start(), leg.end(), leg.transitInfo(), leg.mobilityInfo(), leg.routeCoordinates()
                ));
            } else {
                recomputedLegs.add(leg);
            }
        }

        if (totalDelta == 0) return route;

        int newTotal = route.totalMinutes() + totalDelta;
        log.debug("[접근성] 보행 속도 {}km/h 반영: 총 {}분 → {}분", walkingSpeedKmh, route.totalMinutes(), newTotal);
        // Route의 totalMinutes는 compact constructor에서 legs 기준으로 재계산되지 않으므로 수동 생성
        return new Route(
                route.routeId(), route.type(), newTotal, route.totalCostWon(),
                route.costBreakdown(), route.selectedHubs(), route.evaluation(),
                route.score(), route.recommended(), recomputedLegs,
                route.comparison(), route.insights(), route.carComparison()
        );
    }
}
