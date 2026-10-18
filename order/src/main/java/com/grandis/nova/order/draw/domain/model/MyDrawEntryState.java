package com.grandis.nova.order.draw.domain.model;

/** 내 응모 목록의 상태 배지. 응모 상태 · 회차 마감 · 결과 반영을 합쳐 하나로 보인다. */
public enum MyDrawEntryState {
    /** 응모비를 아직 결제하지 않았고(결제 확인 중 포함) 응모 기간이 남았다 */
    AWAITING_PAYMENT,
    /** 응모비를 결제했고 결과를 아직 반영하지 않았다 */
    AWAITING_RESULT,
    /** 당첨 — 당첨 주문(wonOrderId)이 있다 */
    WON,
    /** 낙첨 */
    LOST,
    /** 결제하지 않은 채로 응모가 마감됐다 — 추첨 대상이 아니다 */
    CLOSED_UNPAID
}
