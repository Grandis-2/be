package com.grandis.nova.order.client.payment;

import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;
import com.grandis.nova.order.order.vo.ShipTo;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayableTargetTest {

    static final Instant AT = Instant.parse("2026-10-11T00:00:00Z");
    static final DrawCampaign CAMPAIGN = new DrawCampaign(TestIds.id(1), TestIds.id(2), TestIds.id(3), "t", "p", "o", null,
            new BigDecimal("300"), 1, AT, AT.plusSeconds(600), AT);

    /** 응모의 결제 금액은 그 회차의 응모비다. 다른 회차를 넘기면 그 회차의 금액으로 결제창이 열리므로 거절한다. */
    @Test
    void drawEntryIsPaidWithItsOwnCampaignFee() {
        DrawEntry entry = entry(CAMPAIGN.id());
        PayableTarget target = PayableTarget.of(entry, CAMPAIGN);

        assertThat(target.type()).isEqualTo("DRAW_ENTRY");
        assertThat(target.id()).isEqualTo(entry.id());
        assertThat(target.amount()).isEqualByComparingTo("300");
        assertThatThrownBy(() -> PayableTarget.of(entry(TestIds.id(9)), CAMPAIGN)).isInstanceOf(IllegalArgumentException.class);
    }

    private static DrawEntry entry(java.util.UUID campaignId) {
        return new DrawEntry(TestIds.id(5), campaignId, TestIds.id(6), DrawEntryStatus.AWAITING_PAYMENT, null,
                new ShipTo("홍길동", "010-0000-0000", "04524", "서울시", null), AT);
    }
}
