package com.grandis.nova.order.order.query;

import com.grandis.nova.order.order.domain.enums.OrderStatus;

/**
 * 예약 취소를 시작해도 되는가. 판정은 {@link OrderStatus#isCancelable()} 하나다. 예약 취소 수신의 배송 거절
 * (PreorderCancelSettlement.settledFor 의 SHIPPED · DELIVERED 줄)은 따로 적힌 규칙이라, 둘이 맞물리는지를
 * CancelabilityAgreementTest 가 모든 상태에서 본다. 조회와 취소 사이에 배송이 시작되면 수신 쪽이 거절한다.
 *
 * @param orderStatus 주문이 없으면 null
 * @param reason      불가면 SHIPPED(SHIPPED · DELIVERED 둘 다), 가능하면 null
 */
public record Cancelability(OrderStatus orderStatus, boolean cancelable, String reason) {

    static final String SHIPPED = "SHIPPED";

    /** 주문이 없다 — 결제 전 예약이다. 정리할 주문이 없으니 취소해도 된다. */
    public static final Cancelability NO_ORDER = new Cancelability(null, true, null);

    public static Cancelability of(OrderStatus status) {
        boolean cancelable = status.isCancelable();
        return new Cancelability(status, cancelable, cancelable ? null : SHIPPED);
    }
}
