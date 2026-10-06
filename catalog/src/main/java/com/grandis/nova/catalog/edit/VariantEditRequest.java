package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.product.SaleStatus;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * PATCH /admin/products/{id}/variants/{variantId}. null 은 "보내지 않음". 바꿀 칸이 하나도 없으면 400.
 * sku · 표시명 · 조합은 바꿀 수 없다 — 구성 변경은 새 옵션 + 기존 옵션 판매 중지(설계 §2.1).
 *
 * @param price  수동 가격. 이후 재계산에서 빠진다
 * @param status ACTIVE · PAUSED. 판매 중지는 신규 주문만 막고 기존 주문은 그대로. 사전예약은 다른 칸처럼 오픈 3분 전부터 못 바꾼다
 * @param resetPrice true 면 수동 가격을 풀고 `기본가 + Σ추가금` 자동 계산으로 되돌린다. price 와 함께 보내면 400
 */
public record VariantEditRequest(@DecimalMin("0") BigDecimal price, SaleStatus status, Boolean resetPrice) {

    public boolean resets() {
        return Boolean.TRUE.equals(resetPrice);
    }

    public boolean isEmpty() {
        return price == null && status == null && !resets();
    }
}
