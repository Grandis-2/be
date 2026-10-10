package com.grandis.nova.order.draw;

import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawPhase;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DrawCampaignTest {

    static final Instant OPENS = Instant.parse("2026-10-11T00:00:00Z");
    static final Instant CLOSES = Instant.parse("2026-10-12T00:00:00Z");

    /** 시작 시각부터 응모 중, 마감 시각부터 마감 — 경계 두 곳을 µs 단위로 본다. */
    @Test
    void phaseFollowsOpensAndClosesAt() {
        DrawCampaign draw = draw(new BigDecimal("100"), 1, OPENS, CLOSES);

        assertThat(draw.phaseAt(OPENS.minusNanos(1_000))).isEqualTo(DrawPhase.SCHEDULED);
        assertThat(draw.phaseAt(OPENS)).isEqualTo(DrawPhase.OPEN);
        assertThat(draw.phaseAt(CLOSES.minusNanos(1_000))).isEqualTo(DrawPhase.OPEN);
        assertThat(draw.phaseAt(CLOSES)).isEqualTo(DrawPhase.CLOSED);
    }

    // ck_draw_campaign_entry_fee · ck_draw_campaign_winner_count · ck_draw_campaign_period 와 같은 규칙
    @Test
    void rejectsWhatTheTableRejects() {
        assertThatThrownBy(() -> draw(new BigDecimal("99"), 1, OPENS, CLOSES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draw(new BigDecimal("100"), 0, OPENS, CLOSES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draw(new BigDecimal("100"), 1, CLOSES, CLOSES)).isInstanceOf(IllegalArgumentException.class);
    }

    private static DrawCampaign draw(BigDecimal fee, int winners, Instant opens, Instant closes) {
        return new DrawCampaign(TestIds.id(1), TestIds.id(2), TestIds.id(3), "t", "p", "o", null, fee, winners, opens, closes, opens);
    }
}
