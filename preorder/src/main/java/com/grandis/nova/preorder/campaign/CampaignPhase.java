package com.grandis.nova.preorder.campaign;

/** 회차를 접수 기간으로 가른 단계. 판매 중지 · 비공개도 일정대로 든다. */
public enum CampaignPhase {
    /** 지금 접수 중(오픈 ≤ 지금 < 마감). 마감 임박순이다. */
    OPEN,
    /** 마감이 지났다. 최근 마감순이다. */
    CLOSED
}
