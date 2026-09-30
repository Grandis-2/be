package com.grandis.nova.preorder.campaign.domain;

import java.time.Instant;

/** 모집 일정과 서버 시각으로 계산한다. 저장하지 않는다 — 저장하면 시각이 지나도 값이 낡는다. */
public enum PreorderSaleStatus {

    BEFORE_OPEN,
    OPEN,
    CLOSED;

    public static PreorderSaleStatus of(PreorderCampaign campaign, Instant now) {
        if (now.isBefore(campaign.getOpensAt())) {
            return BEFORE_OPEN;
        }
        return campaign.isAccepting(now) ? OPEN : CLOSED;
    }
}
