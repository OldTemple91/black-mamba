package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * 장소명 → 좌표 해석 (지오코딩 + POI 검색 폴백).
 * <p>
 * 어댑터가 벤더별 폴백 순서·타임아웃·오류 흡수를 책임진다. 호출자는
 * "찾았다 / 못 찾았다" 만 알면 된다 — 오류는 {@code Optional.empty()} 로 정규화된다.
 */
public interface PlaceResolverPort {

    /** 빈 장소명이면 즉시 {@code Optional.empty()}. 절대 empty Mono 를 내지 않는다 (zip 안전). */
    Mono<Optional<Location>> resolve(String placeName);
}
