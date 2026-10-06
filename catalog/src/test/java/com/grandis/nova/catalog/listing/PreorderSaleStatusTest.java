package com.grandis.nova.catalog.listing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PreorderSaleStatusTest {

    static final Instant OPENS = Instant.parse("2026-10-01T00:00:00Z");
    static final Instant CLOSES = Instant.parse("2026-10-02T00:00:00Z");

    @Test
    @DisplayName("오픈 시각과 같으면 OPEN, 그 1µs 전은 BEFORE_OPEN")
    void openBoundaryIsInclusive() {
        assertThat(PreorderSaleStatus.of(OPENS, CLOSES, OPENS.minusNanos(1_000))).isEqualTo(PreorderSaleStatus.BEFORE_OPEN);
        assertThat(PreorderSaleStatus.of(OPENS, CLOSES, OPENS)).isEqualTo(PreorderSaleStatus.OPEN);
    }

    @Test
    @DisplayName("마감 시각과 같으면 CLOSED, 그 1µs 전은 OPEN")
    void closeBoundaryIsExclusive() {
        assertThat(PreorderSaleStatus.of(OPENS, CLOSES, CLOSES.minusNanos(1_000))).isEqualTo(PreorderSaleStatus.OPEN);
        assertThat(PreorderSaleStatus.of(OPENS, CLOSES, CLOSES)).isEqualTo(PreorderSaleStatus.CLOSED);
    }
}
