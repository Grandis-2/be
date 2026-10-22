package com.grandis.nova.waitingroom.control;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** preorder 용량 행 읽기 — contracts/preorder-capacity-heartbeat.md 의 "대기열이 읽는 법". */
class PreorderCapacityTest {

    static final long NOW = 1_761_098_400L;
    static final CapacityProperties DEFAULTS = CapacityProperties.defaults();
    static final long MAX = 1_500;

    /** 마지막 · 처음 시각은 지금에서 뺀 초다. */
    private static String row(long lastAgo, long firstAgo, long perSecond, long staleAfter) {
        return (NOW - lastAgo) + "|" + (NOW - firstAgo) + "|" + perSecond + "|" + staleAfter;
    }

    @Test
    void 살아_있는_태스크의_초당_용량을_더한다() {
        assertThat(PreorderCapacity.of(Map.of("a", row(3, 600, 100, 15), "b", row(0, 300, 80, 15)), NOW, DEFAULTS, MAX))
                .contains(new PreorderCapacity(180, 2, 0));
    }

    @Test
    void 갓_뜬_태스크는_예열_기간_동안_용량을_절반만_센다() {
        assertThat(PreorderCapacity.of(Map.of("a", row(0, 600, 100, 15), "b", row(0, 119, 100, 15)), NOW, DEFAULTS, MAX))
                .contains(new PreorderCapacity(150, 1, 1));
        assertThat(PreorderCapacity.of(Map.of("b", row(0, 120, 100, 15)), NOW, DEFAULTS, MAX))
                .as("예열 기간이 지나면 다 센다").contains(new PreorderCapacity(100, 1, 0));
        assertThat(PreorderCapacity.of(Map.of("b", row(0, 0, 1, 15)), NOW, DEFAULTS, MAX))
                .as("줄여도 1 은 센다").contains(new PreorderCapacity(1, 0, 1));
    }

    @Test
    void 판정초가_지났거나_시각이_미래인_행은_버린다() {
        assertThat(PreorderCapacity.of(Map.of("dead", row(16, 600, 100, 15), "edge", row(15, 600, 100, 15),
                "future", row(-1, 600, 100, 15)), NOW, DEFAULTS, MAX)).contains(new PreorderCapacity(100, 1, 0));
    }

    @Test
    void 모양이_맞지_않는_행은_버리고_남은_행이_없으면_빈_값이다() {
        Map<String, String> broken = Map.of(
                "parts", NOW + "|" + NOW + "|100",
                "text", NOW + "|" + NOW + "|many|15",
                "zero", row(0, 600, 0, 15),
                "stale0", row(0, 600, 100, 0),
                "firstAfterLast", row(10, 5, 100, 15),
                "blank", "");

        assertThat(PreorderCapacity.of(broken, NOW, DEFAULTS, MAX)).isEmpty();
        assertThat(PreorderCapacity.of(Map.of(), NOW, DEFAULTS, MAX)).as("표시가 없다").isEmpty();
    }

    @Test
    void 합은_운영_최댓값에서_자른다() {
        assertThat(PreorderCapacity.of(Map.of("a", row(0, 600, 1_000, 15), "b", row(0, 600, Long.MAX_VALUE, 15)),
                NOW, DEFAULTS, MAX)).contains(new PreorderCapacity(MAX, 2, 0));
    }

    @Test
    void 예열_기간과_비율은_설정을_따르고_범위를_벗어나면_기동하지_않는다() {
        CapacityProperties shortWarmup = new CapacityProperties(null, null, Duration.ofSeconds(30), 0.25);
        assertThat(PreorderCapacity.of(Map.of("a", row(0, 29, 100, 15), "b", row(0, 30, 100, 15)), NOW, shortWarmup, MAX))
                .contains(new PreorderCapacity(125, 1, 1));

        assertThatThrownBy(() -> new CapacityProperties(null, null, null, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapacityProperties(null, null, null, 1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapacityProperties(null, " ", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapacityProperties(null, null, Duration.ofSeconds(-1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
