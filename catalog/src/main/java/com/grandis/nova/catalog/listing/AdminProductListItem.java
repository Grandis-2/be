package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 관리자 목록의 한 상품 — 회원 목록의 칸 그대로에 관리자만 보는 셋을 더한 것. 회원 목록과 달리 노출 규칙을 거치지 않는다.
 *
 * @param visible               products.visible 칸 그대로 — 회원에게 실제로 보이는가의 앞 조건. 등록이 끝나기 전에는 항상 false 이고,
 *                              관리자가 고른 값은 등록 기록이 들고 있다가 완료 때 옮긴다. 관리자 상세의 product.visible 과 같은 정의
 * @param registrationCompleted 등록 기록이 완료됐다. 기록이 없는 상품(등록 API 이전 행)은 false
 * @param blockedReason         자동으로 이어 갈 수 없는 등록의 사유. 없으면 null
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
        String blockedReason,
        int optionCount,
        BigDecimal minPrice,
        boolean sellable,
        boolean soldOut,
        PreorderSaleStatus preorderStatus,
        Instant opensAt,
        Instant closesAt
) {

    static AdminProductListItem of(ProductListItem item, boolean visible, boolean registrationCompleted, String blockedReason,
                                   int optionCount) {
        return new AdminProductListItem(item.productId(), item.saleMode(), item.title(), item.imageUrl(), item.status(),
                visible, registrationCompleted, blockedReason, optionCount, item.minPrice(), item.sellable(), item.soldOut(),
                item.preorderStatus(), item.opensAt(), item.closesAt());
    }
}
