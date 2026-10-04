package com.grandis.nova.order.order.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.order.order.query.OrderView;

import java.util.List;

/**
 * 관리자 상세. 회원 · 내부 메모가 더 붙는다. 내부 메모는 이 응답에만 있다 —
 * 사용자 응답({@link OrderDetailResponse})에는 칸 자체가 없어 실수로 실릴 길이 없다.
 *
 * @param refundFailed 취소 중인데 환불이 확정 실패했다. 상세 오류는 payment 의 거래 행(last_error_*)에 있다
 */
public record AdminOrderDetailResponse(
        @JsonUnwrapped OrderResponse order,
        Long customerId,
        String internalNote,
        List<OrderEventResponse> events,
        boolean refundFailed
) {

    public static AdminOrderDetailResponse from(OrderView.Detail view) {
        return new AdminOrderDetailResponse(OrderResponse.of(view.order(), view.items()),
                view.order().customerId(), view.order().internalNote(),
                view.events().stream().map(OrderEventResponse::forAdmin).toList(), view.refundFailed());
    }
}
