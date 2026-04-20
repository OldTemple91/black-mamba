package com.blackmamba.navigation.application.route;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 서울 지하철 역 접근성(엘리베이터 유무) 정보 레지스트리.
 * <p>
 * 2024년 말 기준 서울시 전체 지하철역(약 340개) 중 엘리베이터 미설치 역은 극소수.
 * 유지 비용을 최소화하기 위해 <b>엘리베이터 없는 역만</b> 정적 목록으로 관리한다.
 * <p>
 * 향후 개선 방향:
 * - 서울 열린데이터 API (엘리베이터 운영 현황) 연동
 * - 공사 중/고장 상태 실시간 반영
 * - 다른 지역 확장 시 외부 데이터 소스 연결
 */
@Component
public class AccessibilityStationRegistry {

    /**
     * 엘리베이터 미설치 역 목록 (샘플 - 실제 운영 시 공식 데이터로 대체 필요).
     * 이름 매칭은 부분 포함(contains) 방식으로 관대하게 처리.
     */
    private static final Set<String> STATIONS_WITHOUT_ELEVATOR = Set.of(
            // 예시: 실제 현행 목록은 공공데이터포털에서 주기적 갱신
            "남영",          // 1호선 남영역
            "신설동",        // 1호선 신설동역 일부 출구
            "청량리"         // 1호선 청량리역 구 역사 구간
    );

    /**
     * 해당 역이 휠체어 접근 가능한가? (엘리베이터 있음)
     * null/blank 이름은 안전하게 true 반환 (제약 없음).
     */
    public boolean isWheelchairAccessible(String stationName) {
        if (stationName == null || stationName.isBlank()) {
            return true;
        }
        return STATIONS_WITHOUT_ELEVATOR.stream()
                .noneMatch(stationName::contains);
    }
}
