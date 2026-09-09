package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * 장소 검색·지오코딩 (사용자 입력 → 후보 장소 목록 / 좌표).
 * <p>
 * {@link PlaceResolverPort} 가 "장소명 하나 → 최선의 좌표 하나" 라면, 이 포트는
 * 자동완성·주소 변환처럼 <b>후보 목록</b>이 필요한 화면용이다.
 * 벤더 폴백 순서·좌표 검증·오류 흡수는 어댑터가 책임진다 — 실패는 빈 결과로 정규화.
 */
public interface PlaceSearchPort {

    /** 키워드 → 장소 후보 (POI 우선, 없으면 주소/지번 폴백). 결과 없음/오류는 빈 목록. */
    Mono<List<Location>> searchByKeyword(String keyword, int limit);

    /** 주소 → 좌표. 결과 없음/오류는 {@code Optional.empty()}. */
    Mono<Optional<Location>> geocode(String address);

    /** 장소명 자동완성 후보. 결과 없음/오류는 빈 목록. */
    Mono<List<Location>> suggest(String keyword);
}
