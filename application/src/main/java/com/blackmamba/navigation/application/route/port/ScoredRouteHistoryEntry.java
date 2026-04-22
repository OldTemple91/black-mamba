package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.route.RouteHistoryEntry;

/**
 * 벡터 DB 유사 검색 결과에 "유사도 점수" 를 붙인 래퍼.
 * <p>
 * 점수는 벡터 DB 구현에 종속적인 값(정규화 후 코사인 유사도)이므로
 * 도메인({@link RouteHistoryEntry})이 아닌 application 레이어에 둔다.
 * <ul>
 *   <li>코사인 유사도: <b>0.0 ~ 1.0</b>, 1에 가까울수록 유사</li>
 *   <li>bge-m3 는 L2 정규화된 출력을 주므로 dot product = cosine</li>
 * </ul>
 *
 * @param entry           조회된 과거 경로 이력
 * @param similarityScore 쿼리 벡터와의 유사도 (0~1, 높을수록 유사)
 */
public record ScoredRouteHistoryEntry(
        RouteHistoryEntry entry,
        double similarityScore
) {}
