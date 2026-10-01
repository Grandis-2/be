package com.grandis.nova.preorder.campaign.domain;

/** 모집 일정과 서버 시각으로 계산한다({@link PreorderCampaign#saleStatus}). 저장하지 않는다 — 저장하면 시각이 지나도 값이 낡는다. */
public enum PreorderSaleStatus {

    BEFORE_OPEN,
    OPEN,
    CLOSED
}
