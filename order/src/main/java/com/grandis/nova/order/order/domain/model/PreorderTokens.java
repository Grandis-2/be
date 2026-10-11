package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.domain.enums.OrderSource;

/**
 * 주문이 가리키는 예약의 공개 UUID(orders.preorder_token) 규칙. 새 주문(초안)은 ck_order_preorder_token 대로 반드시 채우고,
 * 읽은 주문은 비어 있어도 받는다 — 이 칸을 채우지 않는 버전과 같이 돌 수 있게.
 */
final class PreorderTokens {

    /** preorders.preorder_token 의 길이(char(36)). */
    static final int LENGTH = 36;

    private PreorderTokens() {
    }

    /** 새 주문 — 사전예약 주문만, 그리고 반드시 갖는다. 길이가 다르면 DB 가 잘라 넣거나 거절하기 전에 막는다. */
    static void requireLinked(OrderSource source, String preorderToken) {
        if ((source == OrderSource.PREORDER) && preorderToken == null) {
            throw new IllegalArgumentException("사전예약 주문은 preorderToken 을 가진다");
        }
        requireReadable(source, preorderToken);
    }

    /** 읽은 주문 — 사전예약 주문만 가질 수 있고, 있으면 길이가 맞아야 한다. */
    static void requireReadable(OrderSource source, String preorderToken) {
        if (preorderToken == null) {
            return;
        }
        if (source != OrderSource.PREORDER) {
            throw new IllegalArgumentException("사전예약 주문만 preorderToken 을 가진다: source=" + source);
        }
        if (preorderToken.length() != LENGTH) {
            throw new IllegalArgumentException("preorderToken 은 " + LENGTH + "자다: length=" + preorderToken.length());
        }
    }
}
