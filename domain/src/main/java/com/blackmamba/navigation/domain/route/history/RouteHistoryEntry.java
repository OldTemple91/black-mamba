package com.blackmamba.navigation.domain.route.history;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;

import java.time.Instant;
import java.util.List;

/**
 * 과거 <b>경로 이력 엔트리</b> — 유사 경로 검색의 결과 단위.
 * <p>
 * RAG Phase 2 에서 도입. {@link Route} 를 압축해
 * <ul>
 *   <li><b>description</b> 텍스트 (임베딩 소스 = 의미적 유사도 기준)</li>
 *   <li>필터 메타데이터 (좌표, 시간대, 이동수단 등)</li>
 * </ul>
 * 로 변환해 저장소에 보관한다. 저장소가 좌표를 어떤 공간 인덱스(geohash 등)로
 * 색인하는지는 어댑터의 관심사다 — 도메인은 좌표만 안다.
 *
 * <h3>왜 수치 벡터가 아닌 텍스트를 임베딩하나</h3>
 * 수치 벡터는 거리 유사도만 잡아내지만, 임베딩 모델(bge-m3)은
 * "빠른 지하철 경로", "환승 적은 직행" 같은 <b>의미 유사도</b> 까지 포착한다.
 * 저장소 payload 필터로 공간/시간대는 별도 필터링 가능하므로,
 * 벡터는 "의미", payload 는 "구조"로 역할을 나눈다.
 *
 * @param routeId          원본 Route.routeId (저장소 point id 로도 사용)
 * @param description      임베딩 소스 텍스트 — 사람이 읽어도 이해 가능한 1~2문장
 * @param origin           출발지 좌표
 * @param destination      도착지 좌표
 * @param mobilityTypes    실제 사용된 이동수단 목록 (filter 용)
 * @param routeType        TRANSIT_ONLY / TRANSIT_WITH_MOBILITY 등
 * @param totalMinutes     총 소요 시간
 * @param totalCostWon     총 비용
 * @param preference       검색 시 선호도 (RELIABILITY / TIME_PRIORITY)
 * @param createdAt        저장 시각 (시간대 분석 / TTL 용)
 */
public record RouteHistoryEntry(
        String routeId,
        String description,
        Location origin,
        Location destination,
        List<MobilityType> mobilityTypes,
        RouteType routeType,
        int totalMinutes,
        int totalCostWon,
        String preference,
        Instant createdAt
) {
    public RouteHistoryEntry {
        if (routeId == null || routeId.isBlank()) {
            throw new IllegalArgumentException("routeId 는 필수입니다");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 은 임베딩 소스이므로 필수입니다");
        }
        mobilityTypes = mobilityTypes == null ? List.of() : List.copyOf(mobilityTypes);
        if (createdAt == null) createdAt = Instant.now();
    }
}
