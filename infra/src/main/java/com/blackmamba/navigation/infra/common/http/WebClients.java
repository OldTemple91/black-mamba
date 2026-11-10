package com.blackmamba.navigation.infra.common.http;

import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 외부 API WebClient 공통 구성 — 연결·응답 타임아웃, 응답 JSON 매퍼({@link ExternalApiJson}).
 * <p>
 * 타임아웃 없는 WebClient 는 상대가 응답을 끊지 않고 붙들면 호출이 영원히 대기한다.
 * 회로 차단기는 <b>예외</b>만 집계하므로 "느리지만 결국 성공" 은 장애로 잡히지 않고,
 * 설계해 둔 haversine 폴백도 발동하지 않은 채 상위 {@code block(30s)} 이 504 를 낸다.
 * 모든 외부 클라이언트는 이 팩토리로 WebClient 를 만든다.
 */
public final class WebClients {

    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private WebClients() {
    }

    public static WebClient withTimeout(WebClient.Builder builder, String baseUrl, long timeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        return builder
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // 응답 디코딩은 컨텍스트 설정과 무관하게 외부 API 용 매퍼로 고정
                .codecs(configurer -> {
                    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(ExternalApiJson.MAPPER));
                    // 기본 버퍼 256KB 는 따릉이 1,000행 페이지(약 200KB)에 바짝 붙어 있다. 넘으면 응답 전체가 실패한다
                    configurer.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES);
                })
                .build();
    }
}
