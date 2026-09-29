package com.grandis.nova.order.order.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.order.order.query.OrderView;

/** 관리자 목록 한 줄. 회원이 더 붙는다. */
public record AdminOrderSummaryResponse(
        @JsonUnwrapped OrderResponse order,
        Long customerId
) {

    public static AdminOrderSummaryResponse from(OrderView.Summary view) {
        return new AdminOrderSummaryResponse(OrderResponse.of(view.order(), view.items()),
                view.order().customerId());
    }
}
