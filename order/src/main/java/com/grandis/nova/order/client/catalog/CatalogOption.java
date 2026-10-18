package com.grandis.nova.order.client.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * catalog 가 준 옵션 하나의 사실(contracts/order-internal.md). 살 수 있는지는 order 가 이 칸들로 판정한다.
 * 상태 · 판매 방식은 문자열로 받는다 — catalog 의 enum 에 기대지 않는다(모르는 값은 판매 불가로 읽는다).
 *
 * @param modelName             상품 모델명. 판정에 쓰지 않아 빠져도(배포 순서) null 로 읽는다 — 표시 · 주문 스냅샷만 비는다
 * @param visible               래퍼라 빠지면 null — 판정에서 공개가 아니라고 본다
 * @param registrationCompleted 래퍼라 빠지면 null — 판정에서 준비 전이라고 본다
 */
public record CatalogOption(
        UUID optionId,
        UUID productId,
        String productTitle,
        String modelName,
        String optionTitle,
        String sku,
        BigDecimal price,
        String optionStatus,
        String saleMode,
        String productStatus,
        Boolean visible,
        Boolean registrationCompleted,
        Warranty warranty,
        String imageUrl
) {

    public record Warranty(boolean offered, BigDecimal surcharge) {
    }
}
