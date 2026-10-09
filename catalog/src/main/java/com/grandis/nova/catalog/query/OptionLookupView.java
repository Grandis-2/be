package com.grandis.nova.catalog.query;

import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 옵션 하나와 그 상품의 사실 — 내부 조회 API(GET /internal/options)의 응답 한 줄. 계약: contracts/order-internal.md.
 * order 의 장바구니 표시 · 장바구니 주문 스냅샷이 쓴다. 살 수 있는지는 판정하지 않는다 — 호출자가 칸들로 가린다(비공개 · 판매 중지 · 준비 전도 그대로 싣는다).
 * visible · registrationCompleted 는 primitive 라 늘 실린다(ProductOptionsView 와 같은 이유).
 *
 * @param price                 옵션 최종가(정수 원)
 * @param registrationCompleted 판매 방식별 준비 — 일반은 order 재고 행, 사전예약은 preorder 회차 행이 있다
 * @param warranty              상품의 보증 설정(옵션 문서의 warranty). 제공하지 않으면 offered false · surcharge 0
 * @param imageUrl              상품 썸네일(목록과 같은 규칙 — 첫 색상의 첫 장). 없으면 null
 */
public record OptionLookupView(
        UUID optionId,
        UUID productId,
        String productTitle,
        String optionTitle,
        String sku,
        BigDecimal price,
        SaleStatus optionStatus,
        SaleMode saleMode,
        SaleStatus productStatus,
        boolean visible,
        boolean registrationCompleted,
        Warranty warranty,
        String imageUrl
) {

    /** @param surcharge 보증 추가금(정수 원) */
    public record Warranty(boolean offered, BigDecimal surcharge) {
    }
}
