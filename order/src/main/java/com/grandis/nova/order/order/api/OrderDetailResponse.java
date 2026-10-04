package com.grandis.nova.order.order.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.order.order.query.OrderView;

import java.util.List;

/**
 * 내 주문 상세 = 생성 응답({@link OrderResponse}) + 이력. 생성 응답을 고치지 않고 품는다 —
 * 생성 응답이 늘 빈 이력을 싣거나, 조회 때문에 생성 쪽 DTO 가 바뀌는 일을 막는다. JSON 은 평평하다.
 *
 * @param refundFailed 취소 중인데 환불이 확정 실패했다. 문구("환불이 완료되지 않았습니다. 고객센터로 문의해 주세요.")는 프론트가
 *                     그린다 — 오류 코드는 싣지 않는다(U1)
 */
public record OrderDetailResponse(
        @JsonUnwrapped OrderResponse order,
        List<OrderEventResponse> events,
        boolean refundFailed
) {

    public static OrderDetailResponse from(OrderView.Detail view) {
        return new OrderDetailResponse(OrderResponse.of(view.order(), view.items()),
                view.events().stream().map(OrderEventResponse::forCustomer).toList(), view.refundFailed());
    }
}
