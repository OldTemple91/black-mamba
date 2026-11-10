package com.blackmamba.navigation.api.config;

import com.blackmamba.navigation.application.route.StreamEventPayload.HeartbeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * SSE 이벤트의 전송 형식.
 * <p>
 * application 의 {@link HeartbeatStatus} 는 enum 이고, 클라이언트 계약은 소문자 코드("watching")다.
 * 그 변환은 전송 계층의 일이라 여기서 Jackson 모듈로 등록한다 — application 이 직렬화 라이브러리를 알 필요가 없다.
 */
@Configuration
public class StreamJsonConfig {

    @Bean
    public JacksonModule streamEventModule() {
        SimpleModule module = new SimpleModule("stream-event");
        module.addSerializer(HeartbeatStatus.class, new HeartbeatStatusSerializer());
        return module;
    }

    static final class HeartbeatStatusSerializer extends StdSerializer<HeartbeatStatus> {

        HeartbeatStatusSerializer() {
            super(HeartbeatStatus.class);
        }

        @Override
        public void serialize(HeartbeatStatus value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(value.code());
        }
    }
}
