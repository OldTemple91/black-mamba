package com.blackmamba.navigation.api.debug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관측 스택(Loki ERROR 로그, Tempo 실패 트레이스) 동작을 데모/검증하기 위한 디버그 엔드포인트.
 *
 * <p>운영 배포에서는 활성화되지 않도록 {@code local} 또는 {@code docker} 프로파일에서만 로드.
 * 운영 데모에서 "에러 트레이스/스택트레이스가 Loki+Tempo에 어떻게 상관 연결되는지" 보여주는 용도.
 */
@Profile({"local", "docker"})
@RestController
@RequestMapping("/api/debug")
public class DebugErrorController {

    private static final Logger log = LoggerFactory.getLogger(DebugErrorController.class);

    /**
     * 의도적으로 RuntimeException을 던져 500 응답 + 스택트레이스 로그를 발생시킨다.
     * 예외는 GlobalExceptionHandler가 잡아 로깅 + 500 응답 생성.
     */
    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException("의도적 데모 예외: Loki ERROR + Tempo 실패 트레이스 검증용");
    }

    /**
     * NullPointerException 시뮬레이션. 서로 다른 예외 타입의 스택트레이스 포맷을 비교하기 위함.
     */
    @GetMapping("/npe")
    public String npe() {
        String nullString = null;
        return nullString.toUpperCase();
    }

    /**
     * 느린 요청 시뮬레이션 (2초). Tempo에서 duration 기준 필터로 찾을 수 있다.
     */
    @GetMapping("/slow")
    public String slow() throws InterruptedException {
        log.info("[디버그] 느린 요청 시작 (2초)");
        Thread.sleep(2_000);
        log.info("[디버그] 느린 요청 종료");
        return "slow OK";
    }
}
