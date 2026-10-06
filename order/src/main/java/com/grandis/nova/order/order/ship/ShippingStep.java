package com.grandis.nova.order.order.ship;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;

/**
 * 관리자가 알리는 배송 단계. 관리자 API 는 {@link OrderTrigger} 를 그대로 받지 않는다 — 받으면 취소 · 환불 완료까지 보낼 수 있다.
 *
 * 단계마다 출발 상태가 하나뿐이라(상태 머신이 선형) 원장 전제는 출발 상태 하나로 충분하다. 관리자 화면이 본 상태를
 * 따로 받지 않아도 그사이 취소 · 환불이 시작됐으면 전제가 틀려 적용되지 않는다. ShippingStepTest 가 상태 머신과 맞물림을 본다.
 */
public enum ShippingStep {

    PREPARATION_STARTED(OrderTrigger.PREPARATION_STARTED, OrderStatus.AWAITING_CONFIRMATION, OrderStatus.PREPARING_ITEMS),
    PACKED(OrderTrigger.PACKED, OrderStatus.PREPARING_ITEMS, OrderStatus.READY_TO_SHIP),
    SHIPPED(OrderTrigger.SHIPPED, OrderStatus.READY_TO_SHIP, OrderStatus.SHIPPED),
    DELIVERED(OrderTrigger.DELIVERED, OrderStatus.SHIPPED, OrderStatus.DELIVERED);

    private final OrderTrigger trigger;
    private final OrderStatus from;
    private final OrderStatus to;

    ShippingStep(OrderTrigger trigger, OrderStatus from, OrderStatus to) {
        this.trigger = trigger;
        this.from = from;
        this.to = to;
    }

    public OrderTrigger trigger() {
        return trigger;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }
}
