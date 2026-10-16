package com.blackmamba.navigation.infra.common.http;

/** 외부 API 5xx — 일시 장애일 수 있어 재시도 대상, 회로 차단 집계 대상. */
public class ExternalApiServerException extends ExternalApiException {

    ExternalApiServerException(String apiName, int status, String body) {
        super(apiName, status, body);
    }
}
