package com.blackmamba.navigation.infra.ai;

import com.blackmamba.navigation.application.route.port.ScoredRouteHistoryEntry;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * narrative 검증의 기준이 되는 "프롬프트에 실제로 제공한 수치" 묶음.
 * <p>
 * 예전 검증은 출력의 모든 분/원 값을 <b>경로 총합</b>과만 비교했다. 그런데 프롬프트는 자가용 비교
 * ("자가용보다 12분 빠르며 4,400원 절약") 와 과거 이력 수치를 함께 주고 "유지하라" 고 지시하므로,
 * 지시를 잘 따른 답일수록 총합과 다른 숫자가 들어가 할루시네이션으로 오판됐다.
 * 검증 기준은 "총합과 같은가" 가 아니라 <b>"우리가 준 숫자 중 하나인가"</b> 여야 한다.
 *
 * @param allowedMinutes 프롬프트에 포함된 분 단위 수치 (경로 총합, 구간별, 자가용 차이, 이력)
 * @param allowedWon     프롬프트에 포함된 원 단위 수치
 * @param historyCount   제공한 유사 이력 건수 — "N건 중 M건" 류 주장의 상한
 */
record NarrativeFacts(Set<Integer> allowedMinutes, Set<Integer> allowedWon, int historyCount) {

    private static final Pattern MINUTES_PATTERN = Pattern.compile("(\\d+)\\s*분");
    private static final Pattern WON_PATTERN = Pattern.compile("([\\d,]+)\\s*원");

    static NarrativeFacts of(Route route, String originalNarrative, List<ScoredRouteHistoryEntry> history) {
        Set<Integer> minutes = new LinkedHashSet<>();
        Set<Integer> won = new LinkedHashSet<>();

        minutes.add(route.totalMinutes());
        won.add(route.totalCostWon());
        for (Leg leg : route.legs()) {
            minutes.add(leg.durationMinutes());
        }
        if (route.costBreakdown() != null) {
            route.costBreakdown().items().forEach(item -> won.add(item.amountWon()));
        }
        collect(originalNarrative, minutes, won);

        int count = history == null ? 0 : history.size();
        if (history != null) {
            for (ScoredRouteHistoryEntry entry : history) {
                collect(entry.entry().description(), minutes, won);
                minutes.add(entry.entry().totalMinutes());
                won.add(entry.entry().totalCostWon());
            }
        }
        return new NarrativeFacts(Set.copyOf(minutes), Set.copyOf(won), count);
    }

    /** 텍스트에 등장하는 분/원 수치를 모두 추출해 화이트리스트에 더한다. */
    static void collect(String text, Set<Integer> minutes, Set<Integer> won) {
        if (text == null || text.isBlank()) return;
        Matcher mm = MINUTES_PATTERN.matcher(text);
        while (mm.find()) {
            minutes.add(Integer.parseInt(mm.group(1)));
        }
        Matcher wm = WON_PATTERN.matcher(text);
        while (wm.find()) {
            try {
                won.add(Integer.parseInt(wm.group(1).replace(",", "")));
            } catch (NumberFormatException ignored) {
                // "," 만 있는 토큰 등
            }
        }
    }
}
