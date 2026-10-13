package com.grandis.nova.preorder.campaign.domain;

import java.time.Instant;

/** 모집 일정과 서버 시각으로 계산한다({@link PreorderCampaign#saleStatus}). 저장하지 않는다 — 저장하면 시각이 지나도 값이 낡는다. */
public enum PreorderSaleStatus {

    BEFORE_OPEN,
    OPEN,
    CLOSED;

    /** 오픈 시각 이상, 마감 시각 미만일 때 접수 중이다. */
    public static PreorderSaleStatus of(Instant opensAt, Instant closesAt, Instant now) {
        if (now.isBefore(opensAt)) {
            return BEFORE_OPEN;
        }
        return now.isBefore(closesAt) ? OPEN : CLOSED;
    }
}
