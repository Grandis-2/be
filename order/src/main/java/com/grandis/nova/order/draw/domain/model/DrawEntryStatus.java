package com.grandis.nova.order.draw.domain.model;

/** 응모 상태(ck_draw_entry_status). 환불이 없어 PAID 가 끝이다. */
public enum DrawEntryStatus {
    /** 응모했고 응모비를 아직 결제하지 않았다. 거절 · 결제창 만료면 여기로 돌아와 다시 결제한다 */
    AWAITING_PAYMENT,
    /** 결제창 하나의 승인을 기다린다(authorizing_provider_order_id) */
    AUTHORIZING,
    /** 응모비를 결제했다 — 추첨 대상 */
    PAID
}
