package com.grandis.nova.catalog.product;

/**
 * 상품 · 옵션의 판매 상태. 공개 여부(visible)와 별개다 — 비공개는 회원 목록 · 상세 모두 숨김이고, 상품 PAUSED 는 회원 목록에서 빠지고
 * 상세에는 판매 중지로 보인다(옵션 PAUSED 는 상세에 옵션마다 상태로 실린다). 신규 접수 · 주문은 이 상태를 읽는 preorder · order 가 막는다.
 * 사전예약 오픈 뒤 판매 중지(회차 취소)는 따로 접수한다 — 오픈 전 PAUSED 로 오픈을 넘긴 상품은 판매 중지 그대로다(2026-10-04 결정).
 */
public enum SaleStatus {
    ACTIVE,
    PAUSED
}
