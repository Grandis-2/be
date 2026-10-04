package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.product.SaleStatus;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /admin/products/{id}/sale-status. 상품 단위 판매 시작 · 중지 — 옵션 단위 판매 상태(variants)와 별개다.
 *
 * @param status ACTIVE · PAUSED. 같은 상태를 다시 보내면 바꾸지 않고 그대로 답한다
 * @param reason 사전예약 오픈 뒤 판매 중지(회차 취소)의 사유 — 그때는 필수(앞뒤 공백을 뺀 500자 이하), 그 밖에는 받지 않는다(400).
 *               빈 문자열 · 공백뿐이면 보내지 않은 것으로 본다
 */
public record SaleStatusChangeRequest(@NotNull SaleStatus status, String reason) {

    public SaleStatusChangeRequest(SaleStatus status) {
        this(status, null);
    }
}
