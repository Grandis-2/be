package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.product.SaleStatus;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * PATCH /admin/products/{id}/variants/{variantId}. null 은 "보내지 않음". 둘 다 null 이면 400.
 * sku · 표시명 · 조합은 바꿀 수 없다 — 구성 변경은 새 옵션 + 기존 옵션 판매 중지(설계 §2.1).
 *
 * @param price  수동 가격. 이후 재계산에서 빠진다
 * @param status ACTIVE · PAUSED. 판매 중지는 신규 주문만 막고 기존 주문은 그대로. 사전예약 오픈 뒤에도 바꿀 수 있다(기준정보가 아니다)
 */
public record VariantEditRequest(@DecimalMin("0") BigDecimal price, SaleStatus status) {

    public boolean isEmpty() {
        return price == null && status == null;
    }
}
