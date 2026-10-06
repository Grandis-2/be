package com.grandis.nova.payment.domain.enums;

/**
 * 결제 대상의 종류. 결제는 대상의 상태를 모른다 — 결제할 수 있는지는 부르는 서비스가 판정하고, 결과는 그 서비스가 받는다.
 * 새 대상은 여기와 CHECK(ck_payment_target_type · ck_payment_tx_target_type)를 함께 넓힌다.
 */
public enum TargetType {
    /** 주문(order 서비스). */
    ORDER,
    /** 럭키 드로우 응모(draw 서비스). 응모비 선결제. */
    DRAW_ENTRY
}
