package com.grandis.nova.order.draw.domain.model;

/** 내 응모 목록의 상태 배지. 응모 상태 · 회차 마감 · 결과 반영을 합쳐 하나로 보인다. */
public enum MyDrawEntryState {
    /** 응모비를 아직 결제하지 않았고 응모 기간이 남았다 */
    AWAITING_PAYMENT,
    /** 응모비 결제를 확인하고 있다(승인 중) — 마감 전후와 상관없다. 마감 전에 시작한 결제는 마감 뒤에 승인돼도 추첨 대상이다 */
    PAYMENT_CONFIRMING,
    /** 응모비를 결제했고 결과를 아직 반영하지 않았다 */
    AWAITING_RESULT,
    /** 당첨 — 당첨 주문(wonOrderId)이 있다 */
    WON,
    /** 낙첨 */
    LOST,
    /** 결제 대기인 채로 응모가 마감됐다 — 추첨 대상이 아니다(승인 중이던 결제가 마감 뒤 거절 · 만료돼 결제 대기로 돌아온 응모도 여기) */
    CLOSED_UNPAID
}
