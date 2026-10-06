package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.product.SaleStatus;

/**
 * PATCH /admin/products/{id}/variants/{variantId} — 옵션의 판매 상태(ACTIVE · PAUSED)만 바꾼다. 비면 400.
 * sku · 표시명 · 조합 · 가격은 바꿀 수 없다 — 가격은 기본가 + 고른 값의 추가금 합이라 기본가 · 추가금으로 고치고,
 * 구성 변경은 새 옵션 + 기존 옵션 판매 중지다.
 *
 * @param status ACTIVE · PAUSED. 판매 중지는 신규 주문만 막고 기존 주문은 그대로. 사전예약은 다른 칸처럼 오픈 3분 전부터 못 바꾼다
 */
public record VariantEditRequest(SaleStatus status) {

    public boolean isEmpty() {
        return status == null;
    }
}
