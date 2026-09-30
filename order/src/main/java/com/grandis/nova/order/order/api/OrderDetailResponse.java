package com.grandis.nova.order.order.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.order.order.query.OrderView;

import java.util.List;

/**
 * 내 주문 상세 = 생성 응답({@link OrderResponse}) + 이력. 생성 응답을 고치지 않고 품는다 —
 * 생성 응답이 늘 빈 이력을 싣거나, 조회 때문에 생성 쪽 DTO 가 바뀌는 일을 막는다. JSON 은 평평하다.
 */
public record OrderDetailResponse(
        @JsonUnwrapped OrderResponse order,
        List<OrderEventResponse> events
) {

    public static OrderDetailResponse from(OrderView.Detail view) {
        return new OrderDetailResponse(OrderResponse.of(view.order(), view.items()),
                view.events().stream().map(OrderEventResponse::from).toList());
    }
}
