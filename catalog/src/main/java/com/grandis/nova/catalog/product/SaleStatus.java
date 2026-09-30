package com.grandis.nova.catalog.product;

/**
 * 상품 · 옵션의 판매 상태. PAUSED 는 일반이면 판매 중지, 사전예약 오픈 후면 회차 취소다.
 * 공개 여부(visible)와 별개다 — 비공개는 숨김이고, PAUSED 는 보이되 신규 거래를 막는다.
 */
public enum SaleStatus {
    ACTIVE,
    PAUSED
}
