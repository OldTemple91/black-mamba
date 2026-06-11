package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.route.MobilityInfo;
import com.blackmamba.navigation.domain.route.MobilitySearchHint;
import com.blackmamba.navigation.domain.route.MobilityType;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * "값 없음" 표현 규칙:
 * <ul>
 *   <li>{@code Mono<T>} + empty — 호출부가 "없으면 그 후보를 건너뛰면 되는" 경우
 *       ({@code findSegmentMobility}). {@code Mono.zip} 의 empty-abort 가 곧 원하는 의미.</li>
 *   <li>{@code Mono<Optional<T>>} — 호출부가 "어느 쪽이 없는지 구분해야 하는" 경우
 *       (pickup/dropoff 를 zip 해 NO_PICKUP vs NO_DROPOFF 진단을 나누는 흐름,
 *        hint 처럼 없어도 결과를 유지해야 하는 부가 메타데이터).
 *       empty Mono 로 바꾸면 zip 전체가 중단되어 진단 구분이 불가능해진다.</li>
 * </ul>
 */
public interface MobilityAvailabilityPort {
    Mono<Optional<MobilityInfo>> findNearbyMobility(double lat, double lng, MobilityType type);
    Mono<Optional<MobilityInfo>> findNearbyDropoff(double lat, double lng, MobilityType type);
    /** 픽업+반납이 모두 성립할 때만 값 방출 — 불가하면 empty (해당 조합 스킵). */
    Mono<MobilityInfo> findSegmentMobility(double startLat, double startLng, double endLat, double endLng, MobilityType type);
    Mono<Optional<MobilitySearchHint>> findNearestMobilityHint(double lat, double lng, MobilityType type, boolean dropoff);
}
