package com.grandis.nova.preorder.query.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreorderStatsHistoryPropertiesTest {

    /** 지난 기록은 분 단위로 물으므로 1분보다 짧게 두면 어떤 분도 물을 수 없다. */
    @Test
    void 보관_시간은_1분_이상이어야_한다() {
        assertThatThrownBy(() -> new PreorderStatsHistoryProperties(Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ofSeconds(1), 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new PreorderStatsHistoryProperties(Duration.ofSeconds(1), Duration.ofMinutes(1),
                Duration.ofSeconds(1), 100)).doesNotThrowAnyException();
    }
}
