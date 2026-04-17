package com.blackmamba.navigation.api.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 3: @Observed 어노테이션 활성화.
 * <p>
 * Spring AOP로 @Observed 메서드를 인터셉트하여 Micrometer Observation을 생성하고,
 * 이는 Tracing bridge를 통해 span으로 변환되어 Tempo로 전송된다.
 */
@Configuration
public class ObservationConfig {

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
}
