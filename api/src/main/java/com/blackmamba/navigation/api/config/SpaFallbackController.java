package com.blackmamba.navigation.api.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * React SPA 라우팅 지원.
 * <p>
 * React Router가 관리하는 클라이언트 경로(`/routes`, `/result` 등)는
 * 서버에 해당 핸들러가 없어 브라우저 새로고침 시 404가 발생한다.
 * <p>
 * 해결: 확장자가 없고 / 로 시작하는 모든 GET 요청을 index.html로 포워딩.
 * API(/api/**), Swagger(/swagger-ui, /api-docs), Actuator(/actuator/**),
 * 정적 리소스(/assets, /static, .js/.css 등)는 기존 핸들러가 우선 매핑되므로 영향 없음.
 * <p>
 * PathPatternParser 제약으로 `{*...}` + `/**` 조합이 불가해 단순 변수 기반 매핑 사용.
 */
@Controller
public class SpaFallbackController {

    /**
     * 루트 및 한 depth 경로 (/, /about, /routes 등)
     */
    @GetMapping(value = {"/", "/{path:[^.]*}"})
    public String root() {
        return "forward:/index.html";
    }

    /**
     * 두 depth 이상 경로 (/routes/detail/abc 등) — 확장자 없는 경우만.
     * 정적 리소스는 ResourceHttpRequestHandler가 우선 매핑하므로 충돌 없음.
     */
    @GetMapping("/{path:^(?!api$|swagger-ui$|api-docs$|actuator$|assets$|static$).+}/{rest:[^.]+}")
    public String twoLevel(HttpServletRequest request) {
        return "forward:/index.html";
    }

    @GetMapping("/{path:^(?!api$|swagger-ui$|api-docs$|actuator$|assets$|static$).+}/{rest1:[^.]+}/{rest2:[^.]+}")
    public String threeLevel(HttpServletRequest request) {
        return "forward:/index.html";
    }
}
