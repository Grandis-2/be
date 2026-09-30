package com.grandis.nova.order.order.query;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class CancelabilityTest {

    /* 판정은 OrderStatus.isCancelable() 그대로다. 예약 취소 수신과의 맞물림은 CancelabilityAgreementTest 가 본다. */
    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void followsOrderStatusRule(OrderStatus status) {
        Cancelability cancelability = Cancelability.of(status);

        assertThat(cancelability.orderStatus()).isEqualTo(status);
        assertThat(cancelability.cancelable()).isEqualTo(status.isCancelable());
        assertThat(cancelability.reason()).isEqualTo(status.isCancelable() ? null : "SHIPPED");
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "DELIVERED"})
    void afterShipmentIsNotCancelable(OrderStatus status) {
        assertThat(Cancelability.of(status).cancelable()).isFalse();
    }
}
