package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.domain.enums.OrderSource;

/** 주문이 가리키는 예약의 공개 UUID(orders.preorder_token) 규칙. 초안과 주문이 같은 규칙을 쓴다. */
final class PreorderTokens {

    /** preorders.preorder_token 의 길이(char(36)). */
    static final int LENGTH = 36;

    private PreorderTokens() {
    }

    /** ck_order_preorder_token — 사전예약 주문만, 그리고 반드시 갖는다. 길이가 다르면 DB 가 잘라 넣거나 거절하기 전에 막는다. */
    static void requireLinked(OrderSource source, String preorderToken) {
        if ((source == OrderSource.PREORDER) != (preorderToken != null)) {
            throw new IllegalArgumentException("사전예약 주문만 preorderToken 을 가진다: source=" + source);
        }
        if (preorderToken != null && preorderToken.length() != LENGTH) {
            throw new IllegalArgumentException("preorderToken 은 " + LENGTH + "자다: length=" + preorderToken.length());
        }
    }
}
