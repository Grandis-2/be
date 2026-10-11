package com.grandis.nova.order.draw.domain.model;

/** 추첨 결과. 결과를 반영하기 전에는 없다. 응모비를 결제하지 않은 응모는 추첨 대상이 아니라 반영 뒤에도 없다. */
public enum DrawEntryResult {
    /** 당첨 — 응모 때 배송지로 0원 주문이 생겼다(내 주문 목록) */
    WON,
    LOST
}
