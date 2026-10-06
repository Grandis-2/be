package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 관리자 목록의 한 상품 — 회원 목록의 칸 그대로에 관리자만 보는 셋을 더한 것. 회원 목록과 달리 노출 규칙을 거치지 않는다.
 *
 * @param visible               products.visible 칸 그대로 — 관리자가 고른 공개 여부. 회원에게 실제로 보이는가의 앞 조건이고,
 *                              준비가 안 된 상품은 true 여도 보이지 않는다. 관리자 상세의 product.visible 과 같은 정의
 * @param registrationCompleted 판매 방식별 준비 — 사전예약은 preorder 회차 행, 일반은 order 재고 행이 있다(등록 이벤트가 처리됐다)
 * @param optionCount           옵션 전체 수(판매 중지 포함). 관리 화면의 "N종" 칸 — 사용자 결정 2026-09-28
 * @param preorderStatus        사전예약의 접수 단계. 일반 상품 · 회차 없음은 null
 */
public record AdminProductListItem(
        Long productId,
        SaleMode saleMode,
        String title,
        String imageUrl,
        SaleStatus status,
        boolean visible,
        boolean registrationCompleted,
        int optionCount,
        BigDecimal minPrice,
        boolean sellable,
        boolean soldOut,
        PreorderSaleStatus preorderStatus,
        Instant opensAt,
        Instant closesAt
) {

    static AdminProductListItem of(ProductListItem item, boolean visible, boolean registrationCompleted, int optionCount) {
        return new AdminProductListItem(item.productId(), item.saleMode(), item.title(), item.imageUrl(), item.status(),
                visible, registrationCompleted, optionCount, item.minPrice(), item.sellable(), item.soldOut(),
                item.preorderStatus(), item.opensAt(), item.closesAt());
    }
}
