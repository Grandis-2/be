package com.grandis.nova.order.order.domain.enums;

/** 주문이 어디서 왔는가(ck_order_source). 이번 에픽은 PREORDER 만 만든다. */
public enum OrderSource {
    PREORDER,
    BUY_NOW,
    CART,
    /** 럭키 드로우 당첨 증정(0원). 결제 없이 결제 완료(결제 확인 대기)로 생긴다 — 이 뒤로는 일반 주문과 같은 배송 단계를 탄다 */
    DRAW
}
