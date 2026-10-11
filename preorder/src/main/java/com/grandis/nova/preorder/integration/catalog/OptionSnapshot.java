package com.grandis.nova.preorder.integration.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 접수 시점의 상품 · 옵션 값. 예약 행에 복사해 두므로 이후 카탈로그가 바뀌어도 예약은 그대로다.
 * 상태 문자열은 catalog 소유라 enum 으로 옮기지 않는다 — 값이 늘어도 이 모듈이 깨지지 않게.
 */
public record OptionSnapshot(
        UUID productId,
        String productTitle,
        String saleMode,
        String productStatus,
        UUID optionId,
        String sku,
        String optionTitle,
        BigDecimal price,
        String optionStatus
) {

    static final String PREORDER = "PREORDER";
    static final String ACTIVE = "ACTIVE";

    public boolean isPreorderProduct() {
        return PREORDER.equals(saleMode);
    }

    /** 상품과 옵션이 모두 판매 중. 사전예약은 여기에 회차 기간 안이어야 접수된다. */
    public boolean isOnSale() {
        return ACTIVE.equals(productStatus) && ACTIVE.equals(optionStatus);
    }
}
