package com.blackmamba.navigation.api.support;

import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 요청 파라미터의 이동수단 문자열 → {@link MobilityType} 파싱.
 * <p>
 * 네 컨트롤러가 각자 다른 규칙(콤마 분리 여부, 잘못된 값 처리)으로 파싱하던 것을
 * 두 가지 정책으로 통일한다:
 * <ul>
 *   <li>{@link #parseStrict}: 사용자가 직접 넘긴 값 — 하나라도 틀리면 예외 (400 응답)</li>
 *   <li>{@link #parseLenient}: LLM 출력·검색 필터처럼 느슨한 입력 — 틀린 값만 건너뜀</li>
 * </ul>
 * 두 정책 모두 {@code mobility=A,B} 와 {@code mobility=A&mobility=B} 를 동일하게 받는다.
 */
public final class MobilityTypes {

    private static final Logger log = LoggerFactory.getLogger(MobilityTypes.class);

    private MobilityTypes() {
    }

    /** 허용 값 목록 문구 (400 응답 메시지용). */
    public static String allowedValues() {
        return Arrays.stream(MobilityType.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }

    /**
     * @throws IllegalArgumentException 지원하지 않는 값이 하나라도 있으면
     */
    public static List<MobilityType> parseStrict(List<String> raw) {
        return tokens(raw).stream()
                .map(MobilityType::valueOf)
                .toList();
    }

    /** 지원하지 않는 값은 로그만 남기고 건너뛴다 (전체 요청 실패 방지). */
    public static List<MobilityType> parseLenient(List<String> raw) {
        List<MobilityType> types = new ArrayList<>();
        for (String token : tokens(raw)) {
            try {
                types.add(MobilityType.valueOf(token));
            } catch (IllegalArgumentException _) {
                log.warn("[MobilityTypes] 알 수 없는 mobility \"{}\" 무시", token);
            }
        }
        return types;
    }

    private static List<String> tokens(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .filter(token -> token != null)
                .flatMap(token -> Arrays.stream(token.split(",")))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
    }
}
