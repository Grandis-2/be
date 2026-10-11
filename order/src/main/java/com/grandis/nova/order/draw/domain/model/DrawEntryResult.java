package com.grandis.nova.order.draw.domain.model;

/**
 * 추첨 결과. 결과를 반영하기 전에는 없다. 응모비를 결제하지 않은 응모는 추첨 대상이 아니라 반영 뒤에도 없다.
 * 추첨 요청(또는 전원 당첨 반영) 뒤에 결제 완료된 응모는 추첨에 들어가지 않았어도 낙첨으로 보인다 — 그 응모는 "드로우 추첨 뒤
 * 결제 완료" ERROR 로 잡혀 수동 환불 대상이 된다(DrawEntryPaymentResults).
 */
public enum DrawEntryResult {
    /** 당첨 — 응모 때 배송지로 0원 주문이 생겼다(내 주문 목록) */
    WON,
    LOST
}
