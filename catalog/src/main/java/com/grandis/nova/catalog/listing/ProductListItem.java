package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.product.Brand;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 목록의 한 상품. 회원 목록은 노출 규칙을 지난 상품만 싣는다(판매 방식별 준비 · 공개 · 판매 중 · 사전예약이면 마감 + 120시간 전). 홍보 카드
 * (ProductListingQueryRepository#findCards)는 같은 칸에 마감 뒤 시간 조건만 빼고 싣는다 — 홍보 페이지는 마감 · 일반 전환 뒤에도 상품을 보인다.
 *
 * @param imageUrl       썸네일 — 첫 색상(관리자가 넣은 순서)의 첫 장, 색상 축이 없으면 기본 묶음의 첫 장. 대표 표시는 보지 않고, 첫 색상에 사진이
 *                       없으면 null 이다(다른 색상으로 넘어가지 않는다 — 화면이 대체 이미지). {@link com.grandis.nova.catalog.option.ProductOptions#thumbnailUrl}
 * @param minPrice       판매 중(ACTIVE) 옵션의 최저 할인가(회원이 내는 값 — 가격순도 이 값). 품절 옵션 포함, 판매 중지 옵션 제외. 판매 중 옵션이 없으면 null
 * @param minListPrice   minPrice 옵션의 정가 — 할인율이 상품에 하나라 최저 정가 옵션이 곧 최저 할인가 옵션이다. 판매 중 옵션이 없으면 null
 * @param discountRate   할인율(정수 %, 0~99)
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
        Brand brand,
        String modelName,
        String imageUrl,
        SaleStatus status,
        BigDecimal minPrice,
        BigDecimal minListPrice,
        int discountRate,
        boolean sellable,
        boolean soldOut,
        PreorderSaleStatus preorderStatus,
        Instant opensAt,
        Instant closesAt
) {

    /** 시각으로 정하는 칸(접수 단계)을 now 로 다시 계산한 사본 — 캐시에 든 값이 만든 때의 단계를 내보내지 않게. 계산 규칙은 목록 쿼리와 같다. */
    public ProductListItem at(Instant now) {
        PreorderSaleStatus current = saleMode == SaleMode.PREORDER && opensAt != null && closesAt != null
                ? PreorderSaleStatus.of(opensAt, closesAt, now) : null;
        return new ProductListItem(productId, saleMode, title, brand, modelName, imageUrl, status, minPrice, minListPrice, discountRate,
                sellable, soldOut, current, opensAt, closesAt);
    }
}
