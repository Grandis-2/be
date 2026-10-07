package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;
import com.grandis.nova.order.order.vo.ShipTo;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDraftTest {

    static final String PREORDER_UUID = "0b8f6a3e-5a8c-4d59-9a53-3c1f0e0f7a11";
    static final ShipTo SHIP_TO = new ShipTo("홍길동", "010-0000-0000", "04524", "서울시 중구 세종대로 110", null);

    @Test
    void totalIsSumOfUnitPriceTimesQuantity() {
        OrderDraft draft = new OrderDraft(TestIds.id(1), OrderSource.CART, null, null, SHIP_TO, List.of(
                line(TestIds.id(10), 2, 1000),
                line(TestIds.id(11), 3, 250)));

        assertThat(draft.totalAmount()).isEqualTo(Money.won(2750));
    }

    @Test
    void rejectsSameOptionTwice() {
        assertThatThrownBy(() -> new OrderDraft(TestIds.id(1), OrderSource.CART, null, null, SHIP_TO, List.of(
                line(TestIds.id(10), 1, 1000),
                line(TestIds.id(10), 1, 1000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("optionId=" + TestIds.id(10));
    }

    @Test
    void rejectsEmptyLines() {
        assertThatThrownBy(() -> new OrderDraft(TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, SHIP_TO, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ck_order_preorder_link 와 같은 규칙: 사전예약 주문 ⇔ preorderId 있음
    @Test
    void preorderIdIsRequiredOnlyForPreorderSource() {
        assertThatThrownBy(() -> new OrderDraft(TestIds.id(1), OrderSource.PREORDER, null, PREORDER_UUID, SHIP_TO, List.of(line(TestIds.id(10), 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderDraft(TestIds.id(1), OrderSource.BUY_NOW, TestIds.id(7), null, SHIP_TO, List.of(line(TestIds.id(10), 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ck_order_preorder_token 과 같은 규칙: 사전예약 주문 ⇔ 예약 UUID 있음. 길이는 preorders.preorder_token(char(36))과 같다.
    @Test
    void preorderTokenIsRequiredOnlyForPreorderSource() {
        assertThatThrownBy(() -> new OrderDraft(TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), null, SHIP_TO, List.of(line(TestIds.id(10), 1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preorderToken");
        assertThatThrownBy(() -> new OrderDraft(TestIds.id(1), OrderSource.BUY_NOW, null, PREORDER_UUID, SHIP_TO,
                List.of(line(TestIds.id(10), 1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preorderToken");
        assertThatThrownBy(() -> new OrderDraft(TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), "0b8f6a3e", SHIP_TO,
                List.of(line(TestIds.id(10), 1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("36");
    }

    @Test
    void linesAreCopiedSoCallerCannotChangeThemLater() {
        List<OrderLine> lines = new ArrayList<>(List.of(line(TestIds.id(10), 1, 1000)));
        OrderDraft draft = new OrderDraft(TestIds.id(1), OrderSource.CART, null, null, SHIP_TO, lines);

        lines.add(line(TestIds.id(11), 1, 9999));

        assertThat(draft.lines()).hasSize(1);
        assertThat(draft.totalAmount()).isEqualTo(Money.won(1000));
    }

    static OrderLine line(UUID optionId, int quantity, long unitPrice) {
        return new OrderLine(TestIds.id(100), optionId, new Quantity(quantity), Money.won(unitPrice), "Nova 1", "블랙 / 256GB");
    }
}
