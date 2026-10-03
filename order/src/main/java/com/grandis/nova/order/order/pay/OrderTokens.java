package com.grandis.nova.order.order.pay;

import com.grandis.nova.order.order.vo.OrderToken;

import java.util.Optional;

/** 경로의 주문 공개 토큰. 형식이 틀리면 없는 주문과 같다(존재를 떠볼 수 없게 404). */
final class OrderTokens {

    private OrderTokens() {
    }

    static Optional<OrderToken> parse(String orderToken) {
        try {
            return Optional.of(new OrderToken(orderToken));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
