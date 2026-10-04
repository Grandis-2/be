package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.product.SaleStatus;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /admin/products/{id}/sale-status. 상품 단위 판매 시작 · 중지 — 옵션 단위 판매 상태(variants)와 별개다.
 *
 * @param status ACTIVE · PAUSED. 같은 상태를 다시 보내면 바꾸지 않고 그대로 답한다
 */
public record SaleStatusChangeRequest(@NotNull SaleStatus status) {
}
