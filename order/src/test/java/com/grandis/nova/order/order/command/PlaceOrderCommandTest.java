package com.grandis.nova.order.order.command;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceOrderCommandTest {

    static final String PREORDER_UUID = "0b8f6a3e-5a8c-4d59-9a53-3c1f0e0f7a11";
    static final PlaceOrderCommand.Address ADDRESS =
            new PlaceOrderCommand.Address("홍길동", "010-0000-0000", "04524", "세종대로 110", "");

    @Test
    void convertsToDraftWithValueObjects() {
        PlaceOrderCommand command = new PlaceOrderCommand(1L, OrderSource.PREORDER, 7L, PREORDER_UUID, ADDRESS, List.of(
                new PlaceOrderCommand.Line(100L, 10L, 1, new BigDecimal("1250000"), "Nova 1", "블랙 / 256GB")));

        OrderDraft draft = command.toDraft();

        assertThat(draft.shipTo().line2()).isNull();
        assertThat(draft.lines().getFirst().quantity()).isEqualTo(Quantity.ONE);
        assertThat(draft.totalAmount()).isEqualTo(Money.won(1_250_000));
    }

    @Test
    void toStringHidesShippingAddress() {
        PlaceOrderCommand command = new PlaceOrderCommand(1L, OrderSource.PREORDER, 7L, PREORDER_UUID, ADDRESS, List.of(
                new PlaceOrderCommand.Line(100L, 10L, 1, new BigDecimal("1250000"), "Nova 1", "블랙 / 256GB")));

        assertThat(command.toString()).doesNotContain("홍길동", "010-0000-0000", "04524", "세종대로");
    }

    // 명령은 값만 옮긴다. 규칙 위반은 도메인으로 바꿀 때 드러난다.
    @Test
    void invalidValuesFailOnConversion() {
        PlaceOrderCommand negativePrice = new PlaceOrderCommand(1L, OrderSource.PREORDER, 7L, PREORDER_UUID, ADDRESS, List.of(
                new PlaceOrderCommand.Line(100L, 10L, 1, new BigDecimal("-1"), "Nova 1", "블랙 / 256GB")));

        assertThatThrownBy(negativePrice::toDraft).isInstanceOf(IllegalArgumentException.class);
    }
}
