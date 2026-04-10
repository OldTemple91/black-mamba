package com.blackmamba.navigation.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI blackMambaOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Black Mamba — MaaS 라우팅 엔진 API")
                        .description("""
                                대중교통 + 따릉이 + 개인 PM(전기자전거/전동킥보드)의 \
                                최적 멀티모달 조합을 탐색하는 허브 기반 신뢰도 인식 라우팅 엔진.

                                **핵심 기능:**
                                - 패턴 B/C/D/E 병렬 경로 탐색 (퍼스트마일/라스트마일/혼합)
                                - 6차원 가중 스코어링 (시간/환승/비용/도보/접근도보/신뢰도)
                                - RELIABILITY vs TIME_PRIORITY 이중 추천 축
                                - 추천 이유 + 리스크 배지 제공
                                """)
                        .version("0.5.0")
                        .contact(new Contact()
                                .name("Black Mamba Team")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Local")));
    }
}
