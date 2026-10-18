package com.grandis.nova.order.draw;

import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;
import com.grandis.nova.order.draw.domain.model.MyDrawEntry;
import com.grandis.nova.order.draw.domain.model.MyDrawEntryState;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MyDrawEntryTest {

    static final Instant CLOSES = Instant.parse("2026-10-18T12:00:00Z");

    @Test
    @DisplayName("결제 대기 응모는 마감 전까지 결제 대기, 마감 시각부터 미결제 마감")
    void unpaidTurnsClosedAtCloseInstant() {
        MyDrawEntry entry = entry(DrawEntryStatus.AWAITING_PAYMENT, false, false);
        assertThat(entry.stateAt(CLOSES, CLOSES.minusNanos(1000))).isEqualTo(MyDrawEntryState.AWAITING_PAYMENT);
        assertThat(entry.stateAt(CLOSES, CLOSES)).as("마감 == now 는 마감").isEqualTo(MyDrawEntryState.CLOSED_UNPAID);
    }

    @Test
    @DisplayName("승인 중은 마감 전후와 상관없이 결제 확인 중 — 마감 전에 시작한 결제는 마감 뒤에 승인돼도 추첨 대상이다")
    void authorizingIsConfirmingEvenAfterClose() {
        MyDrawEntry entry = entry(DrawEntryStatus.AUTHORIZING, false, false);
        assertThat(entry.stateAt(CLOSES, CLOSES.minusSeconds(60))).isEqualTo(MyDrawEntryState.PAYMENT_CONFIRMING);
        assertThat(entry.stateAt(CLOSES, CLOSES)).isEqualTo(MyDrawEntryState.PAYMENT_CONFIRMING);
        assertThat(entry.stateAt(CLOSES, CLOSES.plusSeconds(3600))).isEqualTo(MyDrawEntryState.PAYMENT_CONFIRMING);
    }

    @Test
    @DisplayName("결제한 응모는 반영 전이면 결과 대기(마감 뒤에도), 반영 뒤 당첨 주문이 있으면 당첨 · 없으면 낙첨")
    void paidFollowsSettlement() {
        assertThat(entry(DrawEntryStatus.PAID, false, false).stateAt(CLOSES, CLOSES.plusSeconds(60))).isEqualTo(MyDrawEntryState.AWAITING_RESULT);
        assertThat(entry(DrawEntryStatus.PAID, false, false).stateAt(CLOSES, CLOSES.minusSeconds(60))).isEqualTo(MyDrawEntryState.AWAITING_RESULT);
        assertThat(entry(DrawEntryStatus.PAID, true, false).stateAt(CLOSES, CLOSES.plusSeconds(60))).isEqualTo(MyDrawEntryState.LOST);
        assertThat(entry(DrawEntryStatus.PAID, true, true).stateAt(CLOSES, CLOSES.plusSeconds(60))).isEqualTo(MyDrawEntryState.WON);
    }

    private static MyDrawEntry entry(DrawEntryStatus status, boolean settled, boolean won) {
        return new MyDrawEntry(TestIds.id(1), TestIds.id(2), status, CLOSES.minusSeconds(3600), won ? TestIds.id(3) : null, settled);
    }
}
