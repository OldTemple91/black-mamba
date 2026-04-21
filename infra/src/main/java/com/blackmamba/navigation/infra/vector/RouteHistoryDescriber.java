package com.blackmamba.navigation.infra.vector;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.Route;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@link Route} 를 벡터 임베딩용 자연어 서술로 변환한다.
 * <p>
 * bge-m3 같은 임베딩 모델은 텍스트의 의미를 벡터 공간에 매핑한다.
 * 따라서 서술 품질 = 검색 품질. 출발/도착/이동수단/소요/비용/선호도 5가지 축을
 * 한 문장에 담아 "의미적 유사도" 기준 검색이 가능하게 한다.
 *
 * <h3>예시 변환</h3>
 * <pre>
 * Route(TRANSIT_ONLY, 2호선 직행, 36분, 1650원) + preference=TIME_PRIORITY
 *   ↓
 * "강남역에서 홍대입구까지 지하철 직행, 36분 1,650원. 빠른 시간 우선."
 * </pre>
 *
 * <h3>왜 infra 에 두나</h3>
 * "어떻게 서술할지" = 검색 품질을 결정하는 infra 관심사.
 * 도메인/애플리케이션은 "과거 경로 찾아줘" 만 알면 된다.
 */
public final class RouteHistoryDescriber {

    private RouteHistoryDescriber() {}

    public static String describe(Route route, Location origin, Location destination, String preference) {
        StringBuilder sb = new StringBuilder();

        // 1. 출발/도착 (문맥의 닻)
        sb.append(formatLocation(origin)).append("에서 ")
                .append(formatLocation(destination)).append("까지 ");

        // 2. 경로 방식 요약 (TRANSIT / MOBILITY / 조합)
        sb.append(summarizeLegs(route.legs()));

        // 3. 정량 요약
        sb.append(", ").append(route.totalMinutes()).append("분 ")
                .append(String.format(Locale.ROOT, "%,d", route.totalCostWon())).append("원");

        // 4. 선호도 / 추천 사유
        if (preference != null) {
            sb.append(". ").append(preferenceNarrative(preference));
        }
        if (route.recommended()) {
            sb.append(" 추천 경로.");
        }

        return sb.toString();
    }

    /** 서술의 바깥에서 사용자 쿼리도 같은 스타일로 맞추고 싶을 때 쓰는 유틸 (대칭성 확보). */
    public static String describeQuery(String origin, String destination, String preference) {
        StringBuilder sb = new StringBuilder();
        sb.append(origin == null ? "출발지" : origin).append("에서 ")
                .append(destination == null ? "도착지" : destination).append("까지");
        if (preference != null) {
            sb.append(". ").append(preferenceNarrative(preference));
        }
        return sb.toString();
    }

    // ─── 세부 포맷터 ─────────────────────────────

    private static String formatLocation(Location loc) {
        if (loc == null) return "목적지";
        String name = loc.name();
        return (name == null || name.isBlank()) ? "한 지점" : name;
    }

    private static String summarizeLegs(List<Leg> legs) {
        if (legs == null || legs.isEmpty()) return "경로";

        Set<String> modes = new LinkedHashSet<>();
        int transitCount = 0;
        for (Leg leg : legs) {
            switch (leg.type()) {
                case TRANSIT -> {
                    transitCount++;
                    modes.add(transitModeLabel(leg));
                }
                case BIKE -> {
                    if (leg.mobilityInfo() != null && leg.mobilityInfo().mobilityType() == MobilityType.DDAREUNGI) {
                        modes.add("따릉이");
                    } else if (leg.mobilityInfo() != null && leg.mobilityInfo().mobilityType() == MobilityType.PERSONAL_EBIKE) {
                        modes.add("전기자전거");
                    } else {
                        modes.add("자전거");
                    }
                }
                case KICKBOARD -> modes.add("킥보드");
                case WALK -> { /* 도보는 항상 있으므로 별도 안 언급 */ }
            }
        }

        if (modes.isEmpty()) return "도보";

        String combined = String.join("+", modes);
        String transfer = transitCount <= 1 ? " 직행" : " " + (transitCount - 1) + "회 환승";
        return combined + transfer;
    }

    private static String transitModeLabel(Leg leg) {
        String mode = leg.mode();
        if (mode == null || mode.isBlank()) return "대중교통";
        return switch (mode.toUpperCase(Locale.ROOT)) {
            case "SUBWAY", "METRO" -> "지하철";
            case "BUS" -> "버스";
            case "EXPRESS_BUS", "EXPRESSBUS" -> "광역버스";
            default -> "대중교통";
        };
    }

    private static String preferenceNarrative(String preference) {
        return switch (preference.toUpperCase(Locale.ROOT)) {
            case "TIME_PRIORITY" -> "빠른 시간 우선";
            case "RELIABILITY" -> "안정적이고 환승 적은 경로";
            default -> preference;
        };
    }

    /**
     * Leg 에서 LegType 조합을 단순 태그로 반환 (Qdrant payload 필터용).
     */
    public static Set<LegType> extractLegTypes(List<Leg> legs) {
        if (legs == null) return Set.of();
        Set<LegType> types = new LinkedHashSet<>();
        for (Leg leg : legs) if (leg.type() != null) types.add(leg.type());
        return types;
    }

    /**
     * 사용된 이동수단(MobilityType) 목록.
     */
    public static List<MobilityType> extractMobilityTypes(List<Leg> legs) {
        if (legs == null) return List.of();
        Set<MobilityType> seen = new LinkedHashSet<>();
        for (Leg leg : legs) {
            if (leg.mobilityInfo() != null && leg.mobilityInfo().mobilityType() != null) {
                seen.add(leg.mobilityInfo().mobilityType());
            }
        }
        return List.copyOf(seen);
    }
}
