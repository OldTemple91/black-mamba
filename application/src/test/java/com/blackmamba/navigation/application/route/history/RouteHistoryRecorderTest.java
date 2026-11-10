package com.blackmamba.navigation.application.route.history;

import com.blackmamba.navigation.application.route.port.RouteHistoryPort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.domain.route.TransitInfo;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 벡터 DB 에 "의미 있는 이력만" 쌓이게 하는 유일한 방어선 — 0원 추정 경로·중복이 들어가면 RAG narrative 근거가 오염된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouteHistoryRecorderTest {

    @Mock ObjectProvider<RouteHistoryPort> portProvider;
    @Mock RouteHistoryPort port;

    private SimpleMeterRegistry meterRegistry;
    private RouteHistoryRecorder recorder;
    private final Location origin = new Location("서울역", 37.5547, 126.9706);
    private final Location destination = new Location("강남역", 37.4979, 127.0276);

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(portProvider.getIfAvailable()).thenReturn(port);
        when(port.save(any(), any(), any(), any())).thenReturn(Mono.empty());
        recorder = new RouteHistoryRecorder(portProvider, meterRegistry);
    }

    @Test
    void 품질_게이트를_통과한_추천_경로만_저장한다() {
        // given
        Route good = transitRoute(45, 1_250).withScore(0.8, true);
        Route notRecommended = transitRoute(40, 1_250).withScore(0.7, false);

        // when
        recorder.recordAsync(List.of(good, notRecommended), origin, destination, RecommendationPreference.RELIABILITY);

        // then
        verify(port, times(1)).save(any(), any(), any(), any());
        assertThat(meterRegistry.get("navigation.rag.history.saved").counter().count()).isEqualTo(1.0);
    }

    @Test
    void 짧거나_무료이거나_도보뿐인_경로는_품질_게이트에서_제외된다() {
        // given — 4분 / 0원 / 도보만
        Route tooShort = transitRoute(4, 1_250).withScore(0.8, true);
        Route free = Route.of(List.of(new Leg(LegType.TRANSIT, "SUBWAY", 30, 9_000, origin, destination, null, null, null)), RouteType.TRANSIT_ONLY).withScore(0.8, true);
        Route walkOnly = Route.of(List.of(new Leg(LegType.WALK, "WALK", 12, 900, origin, destination, null, null, null)), RouteType.TRANSIT_ONLY).withScore(0.8, true);

        // when
        recorder.recordAsync(List.of(tooShort, free, walkOnly), origin, destination, RecommendationPreference.RELIABILITY);

        // then
        verify(port, never()).save(any(), any(), any(), any());
        assertThat(meterRegistry.get("navigation.rag.history.rejected").tag("reason", "quality_gate").counter().count()).isEqualTo(3.0);
    }

    @Test
    void 같은_OD와_선호도는_60초_안에_한_번만_저장한다() {
        // given
        Route route = transitRoute(45, 1_250).withScore(0.8, true);
        Location nearbyOrigin = new Location("서울역 근처", 37.5551, 126.9709);   // 3자리 반올림 시 같은 키

        // when
        recorder.recordAsync(List.of(route), origin, destination, RecommendationPreference.RELIABILITY);
        recorder.recordAsync(List.of(route), nearbyOrigin, destination, RecommendationPreference.RELIABILITY);
        recorder.recordAsync(List.of(route), origin, destination, RecommendationPreference.TIME_PRIORITY);

        // then — 선호도가 다르면 별도 저장
        verify(port, times(2)).save(any(), any(), any(), any());
        assertThat(meterRegistry.get("navigation.rag.history.rejected").tag("reason", "duplicate").counter().count()).isEqualTo(1.0);
    }

    @Test
    void 저장이_실패하면_saved_카운터는_오르지_않는다() {
        // given
        when(port.save(any(), any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("qdrant down")));
        Route route = transitRoute(45, 1_250).withScore(0.8, true);

        // when
        recorder.recordAsync(List.of(route), origin, destination, RecommendationPreference.RELIABILITY);

        // then
        assertThat(meterRegistry.get("navigation.rag.history.saved").counter().count()).isZero();
    }

    @Test
    void 포트가_없으면_아무것도_하지_않는다() {
        // given
        when(portProvider.getIfAvailable()).thenReturn(null);
        RouteHistoryRecorder disabled = new RouteHistoryRecorder(portProvider, new SimpleMeterRegistry());

        // when & then — 예외 없음
        disabled.recordAsync(List.of(transitRoute(45, 1_250).withScore(0.8, true)), origin, destination, RecommendationPreference.RELIABILITY);
        verify(port, never()).save(any(), any(), any(), any());
    }

    private Route transitRoute(int minutes, int fare) {
        Leg subway = new Leg(LegType.TRANSIT, "SUBWAY", minutes, 9_000, origin, destination,
                new TransitInfo("4호선", null, 8, fare, List.of()), null, null);
        return Route.of(List.of(subway), RouteType.TRANSIT_ONLY);
    }
}
