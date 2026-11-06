package com.blackmamba.navigation.api.support;

import reactor.core.Exceptions;

import java.util.concurrent.TimeoutException;

/**
 * 컨트롤러의 {@code .timeout(..).block()} 에서 올라온 예외가 타임아웃인지 판별한다.
 * <p>
 * 예전엔 {@code block(Duration)} 이 던지는 {@link IllegalStateException} 을 잡아 504 로 답했는데,
 * 전략·어댑터의 모든 IllegalStateException 이 "타임아웃" 으로 기록되고 스택이 버려졌다.
 * Reactor 는 checked {@link TimeoutException} 을 RuntimeException 으로 감싸므로 unwrap 해서 본다.
 */
public final class ReactorTimeouts {

    private ReactorTimeouts() {
    }

    public static boolean isTimeout(Throwable throwable) {
        return Exceptions.unwrap(throwable) instanceof TimeoutException;
    }
}
