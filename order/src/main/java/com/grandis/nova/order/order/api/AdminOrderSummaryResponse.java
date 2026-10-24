package com.grandis.nova.order.order.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.order.order.query.OrderView;

import java.util.UUID;

/**
 * 관리자 목록 한 줄. 회원과 예약이 더 붙는다.
 *
 * @param customerName 회원 이름(member 가 가린 값, 예: 김**). member 에게 받지 못했으면 null
 * @param preorderId   사전예약 주문이 가리키는 예약의 공개 UUID(preorder 의 preorderId). 사전예약 주문이 아니면 null
 */
public record AdminOrderSummaryResponse(
        @JsonUnwrapped OrderResponse order,
        UUID customerId,
        String customerName,
        String preorderId
) {

    public static AdminOrderSummaryResponse from(OrderView.Summary view, String customerName) {
        return new AdminOrderSummaryResponse(OrderResponse.of(view.order(), view.items()),
                view.order().customerId(), customerName, view.order().preorderToken());
    }
}
