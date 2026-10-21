package com.grandis.nova.order.draw;

import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawStage;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 회차 진행 단계 — 시작 · 마감 · 마감 + 멈춤 기준의 경계, 반영 시각이 있으면 시각과 상관없이 발표. */
class DrawStageTest {

    private static final Instant OPENS = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant CLOSES = Instant.parse("2026-10-22T00:00:00Z");
    private static final DrawCampaign CAMPAIGN = new DrawCampaign(TestIds.id(1), TestIds.id(2), TestIds.id(3), "t", "p", null, "o", null,
            new BigDecimal("100"), 1, OPENS, CLOSES, OPENS);

    @Test
    void followsOpeningAndClosing() {
        assertThat(stage(OPENS.minusNanos(1000))).isEqualTo(DrawStage.SCHEDULED);
        assertThat(stage(OPENS)).isEqualTo(DrawStage.ENTRY_OPEN);
        assertThat(stage(CLOSES.minusNanos(1000))).isEqualTo(DrawStage.ENTRY_OPEN);
        assertThat(stage(CLOSES)).isEqualTo(DrawStage.DRAWING);
    }

    @Test
    void unsettledAfterStallIsStuck() {
        Instant stalled = CLOSES.plus(DrawStage.STALL_AFTER);

        assertThat(stage(stalled.minusNanos(1000))).isEqualTo(DrawStage.DRAWING);
        assertThat(stage(stalled)).isEqualTo(DrawStage.STUCK);
        assertThat(stage(stalled.plus(Duration.ofDays(30)))).isEqualTo(DrawStage.STUCK);
    }

    @Test
    void settledIsAnnouncedWhateverTheTime() {
        Instant settledAt = CLOSES.plusSeconds(30);

        assertThat(DrawStage.of(CAMPAIGN, settledAt, settledAt)).isEqualTo(DrawStage.WINNERS_ANNOUNCED);
        assertThat(DrawStage.of(CAMPAIGN, settledAt, CLOSES.plus(Duration.ofDays(2)))).isEqualTo(DrawStage.WINNERS_ANNOUNCED);
    }

    private static DrawStage stage(Instant now) {
        return DrawStage.of(CAMPAIGN, null, now);
    }
}
