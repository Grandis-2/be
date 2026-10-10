package com.grandis.nova.order.order.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.order.order.place.PlaceResult;

/**
 * 주문 생성 응답 = 주문({@link OrderResponse}) + 이번 요청이 기존 주문을 돌려받았는가. JSON 은 평평하다(명세 POST /orders 의 reused).
 *
 * @param reused 새로 만들지 않고 기존 주문을 돌려줬다 — 사전예약은 그 예약의 주문, 장바구니는 값까지 같은 결제 전 주문(200)
 */
public record PlaceOrderResponse(@JsonUnwrapped OrderResponse order, boolean reused) {

    static PlaceOrderResponse of(PlaceResult result) {
        return new PlaceOrderResponse(OrderResponse.of(result.order(), result.items()), !result.created());
    }
}
