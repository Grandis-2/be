package com.grandis.nova.order.draw;

import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.OrderToken;
import com.grandis.nova.order.order.vo.Quantity;
import com.grandis.nova.order.order.vo.ShipTo;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 당첨 증정 주문 · 당첨 응모의 도메인 규칙(ck_order_due · ck_draw_entry_won_paid 와 같다). */
class DrawOrderRulesTest {

    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final ShipTo SHIP_TO = new ShipTo("홍길동", "010-0000-0000", "04524", "서울시", null);

    @Test
    @DisplayName("드로우 주문은 0원 · 결제 기한 없음 · 결제 확인 대기로 생긴다 — 0원이 아니면 거절")
    void drawOrderIsFreeAndAlreadyPaid() {
        Order order = Order.place(draft(Money.ZERO), OrderToken.issue(), NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_CONFIRMATION);
        assertThat(order.paymentDueAt()).isNull();
        assertThatThrownBy(() -> Order.place(draft(new Money(new BigDecimal("100"))), OrderToken.issue(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("당첨 주문은 결제 완료 응모에만 있다")
    void onlyPaidEntryWins() {
        assertThatThrownBy(() -> new DrawEntry(TestIds.id(1), TestIds.id(2), TestIds.id(3), DrawEntryStatus.AWAITING_PAYMENT, null, SHIP_TO, NOW,
                TestIds.id(4))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new DrawEntry(TestIds.id(1), TestIds.id(2), TestIds.id(3), DrawEntryStatus.PAID, null, SHIP_TO, NOW, TestIds.id(4))
                .wonOrderId()).isEqualTo(TestIds.id(4));
    }

    private static OrderDraft draft(Money price) {
        return new OrderDraft(TestIds.id(9), OrderSource.DRAW, null, null, SHIP_TO,
                List.of(OrderLine.withoutWarranty(TestIds.id(7), TestIds.id(8), Quantity.ONE, price, "증정품", "블랙")));
    }
}
