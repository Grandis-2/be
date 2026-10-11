package com.grandis.nova.order.order.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.order.order.query.OrderView;

import java.util.List;
import java.util.UUID;

/**
 * 관리자 상세. 회원이 더 붙고, 이력의 관리자 사유도 싣는다 — 사용자 상세({@link OrderDetailResponse})는 관리자 사유를 비운다.
 *
 * @param refundFailed 취소 중인데 환불이 확정 실패했다. 상세 오류는 payment 의 거래 행(last_error_*)에 있다
 */
public record AdminOrderDetailResponse(
        @JsonUnwrapped OrderResponse order,
        UUID customerId,
        List<OrderEventResponse> events,
        boolean refundFailed
) {

    public static AdminOrderDetailResponse from(OrderView.Detail view) {
        return new AdminOrderDetailResponse(OrderResponse.of(view.order(), view.items()), view.order().customerId(),
                view.events().stream().map(OrderEventResponse::forAdmin).toList(), view.refundFailed());
    }
}
