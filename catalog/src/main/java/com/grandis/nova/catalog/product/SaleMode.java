package com.grandis.nova.catalog.product;

/** 판매 유형. 등록 뒤 바꾸지 않는다 — 사전예약은 회차 행이, 일반은 재고 행이 따라붙는다. */
public enum SaleMode {
    PREORDER,
    IN_STOCK
}
