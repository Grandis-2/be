package com.grandis.nova.catalog.listing;

import java.time.Instant;

/** 사전예약 상품의 접수 단계. 회차 시각과 서버 시각으로 계산하고 저장하지 않는다. 일반 상품은 null 이다. */
public enum PreorderSaleStatus {

    BEFORE_OPEN,
    OPEN,
    CLOSED;

    /** 오픈 시각 이상 · 마감 시각 미만이면 OPEN. */
    public static PreorderSaleStatus of(Instant opensAt, Instant closesAt, Instant now) {
        if (now.isBefore(opensAt)) {
            return BEFORE_OPEN;
        }
        return now.isBefore(closesAt) ? OPEN : CLOSED;
    }
}
