package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.query.Cancelability;

/**
 * 취소 가능 여부 응답. 계약 예시의 preorderId(예약 공개 UUID)는 싣지 않는다 — order 는 그 값을 모르고,
 * 호출자(preorder)는 cancelable · orderStatus 만 쓴다.
 *
 * @param orderStatus 주문이 없으면 null
 * @param reason      불가면 SHIPPED, 가능하면 null
 */
public record CancelabilityResponse(OrderStatus orderStatus, boolean cancelable, String reason) {

    static CancelabilityResponse from(Cancelability cancelability) {
        return new CancelabilityResponse(cancelability.orderStatus(), cancelability.cancelable(),
                cancelability.reason());
    }
}
