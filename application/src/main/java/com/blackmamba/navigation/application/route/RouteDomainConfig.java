package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.comparison.CarReferenceCalculator;
import com.blackmamba.navigation.domain.route.comparison.CarbonFootprintCalculator;
import com.blackmamba.navigation.domain.route.evaluation.RouteInsightFactory;
import com.blackmamba.navigation.domain.route.evaluation.RouteScoreCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 도메인 정책 객체의 빈 등록.
 * <p>
 * domain 모듈은 Spring 을 모른다 ({@code @Component} 없음) — 순수 계산 정책은
 * 프레임워크 없이도 {@code new} 로 쓸 수 있어야 하고, 실제로 도메인 테스트가 그렇게 쓴다.
 * 컨테이너에 올리는 일은 application 의 책임이다.
 */
@Configuration
public class RouteDomainConfig {

    @Bean
    public RouteScoreCalculator routeScoreCalculator() {
        return new RouteScoreCalculator();
    }

    @Bean
    public RouteInsightFactory routeInsightFactory() {
        return new RouteInsightFactory();
    }

    @Bean
    public CarbonFootprintCalculator carbonFootprintCalculator() {
        return new CarbonFootprintCalculator();
    }

    @Bean
    public CarReferenceCalculator carReferenceCalculator(CarbonFootprintCalculator carbonFootprintCalculator) {
        return new CarReferenceCalculator(carbonFootprintCalculator);
    }
}
