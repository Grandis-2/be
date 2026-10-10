package com.grandis.nova.order.draw.domain.model;

/** 추첨 결과. 결과를 반영하기 전에는 없다. 결제하지 않은 응모(추첨 대상 아님)도 반영 뒤에는 낙첨이다. */
public enum DrawEntryResult {
    /** 당첨 — 응모 때 배송지로 0원 주문이 생겼다(내 주문 목록) */
    WON,
    LOST
}
