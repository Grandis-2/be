package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 목록의 한 상품. 노출 규칙을 지난 상품만 여기 온다(판매 방식별 준비 · 공개 · 판매 중 · 사전예약이면 마감 + 120시간 전).
 *
 * @param imageUrl       대표 사진. 기본 묶음('')의 대표가 있으면 그것, 없으면 첫 색상 묶음의 대표. 사진이 없으면 null(화면이 대체 이미지)
 * @param minPrice       판매 중(ACTIVE) 옵션의 최저가. 품절 옵션 포함, 판매 중지 옵션 제외. 판매 중 옵션이 없으면 null
 * @param sellable       판매 중(ACTIVE) 옵션이 하나 이상 있다. false 면 옵션이 없거나 전부 판매 중지 — 화면이 "판매 중지" 를 그린다.
 *                       상품 자체의 판매 중지(status PAUSED)와 달리 목록에 남는다
 * @param soldOut        일반 상품에서 판매 중 옵션의 가용 재고가 전부 0 이하(재고 행이 없는 옵션은 판매 불가). 사전예약은 재고 행이 없으므로
 *                       품절이 되지 않는다 — 옵션이 전부 판매 중지여도 false(사용자 결정 2026-09-27, 제안서 §2)
 * @param preorderStatus 사전예약의 접수 단계. 일반 상품은 null
 */
public record ProductListItem(
        UUID productId,
        SaleMode saleMode,
        String title,
        String imageUrl,
        SaleStatus status,
        BigDecimal minPrice,
        boolean sellable,
        boolean soldOut,
        PreorderSaleStatus preorderStatus,
        Instant opensAt,
        Instant closesAt
) {
}
