package com.blackmamba.navigation.api.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * React SPA 라우팅 지원.
 *
 * React Router가 관리하는 클라이언트 경로(`/routes`, `/result` 등)는
 * 서버에 해당 핸들러가 없기 때문에 브라우저 새로고침 시 404가 발생한다.
 *
 * 해결: API, Swagger, Actuator, 정적 리소스(.js/.css/.png 등)를 제외한
 * 모든 GET 경로를 index.html로 포워딩해 React가 라우팅을 처리하게 한다.
 */
@Controller
public class SpaFallbackController {

    @GetMapping(value = {
            "/",
            "/{path:[^\\.]*(?<!\\.map)$}",
            "/{path:^(?!api|swagger-ui|api-docs|actuator|assets|static).*}/**/{subpath:[^\\.]*(?<!\\.map)$}"
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
