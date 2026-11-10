package com.blackmamba.navigation.application.route;

/**
 * 탐색을 시작할 수 없는 요청 — 사용자 입력의 문제이므로 400 으로 응답한다.
 * 사유 코드는 API 오류 코드로 그대로 쓰인다.
 */
public class InvalidRouteSearchException extends IllegalArgumentException {

    public enum Reason {
        INVALID_LATITUDE,
        INVALID_LONGITUDE,
        SHORT_DISTANCE
    }

    private final Reason reason;

    public InvalidRouteSearchException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String code() {
        return reason.name();
    }
}
