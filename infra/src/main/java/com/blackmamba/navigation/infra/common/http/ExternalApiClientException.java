package com.blackmamba.navigation.infra.common.http;

/** 외부 API 4xx (키 오류·한도 초과·잘못된 요청) — 재시도해도 같은 답이라 회로 차단 집계만. */
public class ExternalApiClientException extends ExternalApiException {

    ExternalApiClientException(String apiName, int status, String body) {
        super(apiName, status, body);
    }
}
