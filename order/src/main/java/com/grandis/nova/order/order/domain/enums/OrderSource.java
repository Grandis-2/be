package com.grandis.nova.order.order.domain.enums;

/** 주문이 어디서 왔는가(ck_order_source). 이번 에픽은 PREORDER 만 만든다. */
public enum OrderSource {
    PREORDER,
    BUY_NOW,
    CART
}
