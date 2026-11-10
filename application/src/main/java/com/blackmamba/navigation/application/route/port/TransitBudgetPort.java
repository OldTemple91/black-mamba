package com.blackmamba.navigation.application.route.port;

/**
 * 대중교통 조회 API 의 남은 호출 예산. 전략이 탐색 폭을 정할 때 읽는다.
 * 집계와 한도는 infra 가 안다 — application 은 수준만 받는다.
 */
public interface TransitBudgetPort {

    /** 예산 제한이 없는 구현 — 한도 개념이 없는 조회원이나 테스트용 */
    TransitBudgetPort UNLIMITED = () -> BudgetLevel.NORMAL;

    BudgetLevel level();
}
