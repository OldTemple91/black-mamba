package com.blackmamba.navigation.application.route;

public enum SearchMode {
    SPECIFIC,  // 사용자가 이동수단 직접 선택 → 라스트마일만 탐색
    OPTIMAL    // 알고리즘이 5가지 패턴 × 3가지 수단 자동 탐색
}
