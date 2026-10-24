package com.grandis.nova.order.order.query;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderEvent;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.ShipTo;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderViewTest {

    static final ShipTo SHIP_TO = new ShipTo("홍길동", "010-0000-0000", "04524", "서울시 중구 세종대로 110", null);

    // 취소 중 주문은 환불 실패 시각으로 정한다 — 마지막 이력이 무엇이든 같다. 취소된 주문(자동 환불)은 실패 시각에 더해 마지막 이력이
    // 환불 실패일 때만이다 — 그 뒤 해소 이력이 붙으면 실패로 보지 않는다
    @Test
    void refundFailureFollowsFailedAtAndForCanceledTheLastEvent() {
        OrderEvent refundFailure = event(EventCause.refundFailed());
        OrderEvent later = event(EventCause.system("ANY"));

        assertThat(detail(OrderStatus.CANCELING, Instant.EPOCH, later).refundFailed()).isTrue();
        assertThat(detail(OrderStatus.CANCELING, null, refundFailure).refundFailed()).isFalse();
        assertThat(detail(OrderStatus.CANCELED, Instant.EPOCH, refundFailure).refundFailed()).as("자동 환불 실패").isTrue();
        assertThat(detail(OrderStatus.CANCELED, Instant.EPOCH, later).refundFailed()).as("실패 뒤 해소").isFalse();
        assertThat(detail(OrderStatus.CANCELED, null, refundFailure).refundFailed()).isFalse();
    }

    private static OrderView.Detail detail(OrderStatus status, Instant refundFailedAt, OrderEvent lastEvent) {
        Order order = new Order(TestIds.id(1), TestIds.id(2), OrderSource.PREORDER, TestIds.id(3),
                "0b8f6a3e-5a8c-4d59-9a53-3c1f0e0f7a11", null, status, null, Money.won(1000), null, null, refundFailedAt, null, SHIP_TO, 3,
                Instant.EPOCH, Instant.EPOCH);
        return new OrderView.Detail(order, List.of(), List.of(lastEvent));
    }

    private static OrderEvent event(EventCause cause) {
        return new OrderEvent(TestIds.id(1), 3, OrderStatus.CANCELING, OrderStatus.CANCELING, cause, Instant.EPOCH);
    }
}
