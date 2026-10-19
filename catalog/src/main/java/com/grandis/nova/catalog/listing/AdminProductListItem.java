package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.product.Brand;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 목록의 한 상품 — 회원 목록의 칸 그대로에 관리자만 보는 셋을 더한 것. 회원 목록과 달리 노출 규칙을 거치지 않는다.
 *
 * @param visible               products.visible 칸 그대로 — 관리자가 고른 공개 여부. 회원에게 실제로 보이는가의 앞 조건이고,
 *                              준비가 안 된 상품은 true 여도 보이지 않는다. 관리자 상세의 product.visible 과 같은 정의
 * @param registrationCompleted 판매 방식별 준비 — 사전예약은 preorder 회차가 있다(회차 일정 API), 일반은 order 의 판매 준비 이벤트를 받았다(products.stock_ready_at)(등록 이벤트가 처리됐다)
 * @param optionCount           옵션 전체 수(판매 중지 포함). 관리 화면의 "N종" 칸 — 사용자 결정 2026-09-28
 * @param preorderStatus        사전예약의 접수 단계. 일반 상품 · 회차 없음은 null
 */
public record AdminProductListItem(
        UUID productId,
        SaleMode saleMode,
        String title,
        Brand brand,
        String modelName,
        String imageUrl,
        SaleStatus status,
        boolean visible,
        boolean registrationCompleted,
        int optionCount,
        BigDecimal minPrice,
        BigDecimal minListPrice,
        int discountRate,
        boolean sellable,
        boolean soldOut,
        PreorderSaleStatus preorderStatus,
        Instant opensAt,
        Instant closesAt
) {

    /** 품절만 바꾼 사본({@link SoldOutMarker}). */
    AdminProductListItem withSoldOut(boolean replaced) {
        return new AdminProductListItem(productId, saleMode, title, brand, modelName, imageUrl, status, visible, registrationCompleted, optionCount,
                minPrice, minListPrice, discountRate, sellable, replaced, preorderStatus, opensAt, closesAt);
    }

    /** 사전예약의 회차를 채운 사본 — 회차가 있으면 준비가 끝났다, 없으면(null) 등록 이벤트 처리 전. 일반 상품은 그대로. */
    AdminProductListItem withCampaign(CampaignWindow window, Instant now) {
        if (saleMode != SaleMode.PREORDER) {
            return this;
        }
        return new AdminProductListItem(productId, saleMode, title, brand, modelName, imageUrl, status, visible, window != null, optionCount,
                minPrice, minListPrice, discountRate, sellable, soldOut, window == null ? null : window.statusAt(now),
                window == null ? null : window.opensAt(), window == null ? null : window.closesAt());
    }

    static AdminProductListItem of(ProductListItem item, boolean visible, boolean registrationCompleted, int optionCount) {
        return new AdminProductListItem(item.productId(), item.saleMode(), item.title(), item.brand(), item.modelName(), item.imageUrl(), item.status(),
                visible, registrationCompleted, optionCount, item.minPrice(), item.minListPrice(), item.discountRate(), item.sellable(), item.soldOut(),
                item.preorderStatus(), item.opensAt(), item.closesAt());
    }
}
