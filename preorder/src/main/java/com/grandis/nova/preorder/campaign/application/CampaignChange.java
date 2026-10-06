package com.grandis.nova.preorder.campaign.application;

/** 회차 변경 이벤트를 낸 까닭. 로그 · 추적용이고 받는 쪽 판정은 일정 번호로 한다. */
public enum CampaignChange {
    CREATED,
    RESCHEDULED,
    CLOSED,
    /** catalog 에서 상품 공개 여부가 바뀌었다. */
    VISIBILITY,
    /** 바뀐 것 없이 현재 일정을 다시 보낸다(전체 재발행). */
    RESYNC
}
