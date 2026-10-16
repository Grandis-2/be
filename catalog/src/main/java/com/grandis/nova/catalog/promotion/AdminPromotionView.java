package com.grandis.nova.catalog.promotion;

import com.grandis.nova.catalog.product.SaleMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 홍보 한 건 — 목록 · 상세 · 만들기 · 고치기 응답.
 *
 * @param status   오늘(한국 날짜)과 홍보 기간으로 가른 단계
 * @param products 묶인 상품(순서대로). 일반으로 바꾼 상품도 묶음은 남아 saleMode 가 IN_STOCK 이다
 */
public record AdminPromotionView(
        UUID promotionId,
        String name,
        PromotionStatus status,
        LocalDate startDate,
        LocalDate endDate,
        String thumbnailUrl,
        List<String> detailImageUrls,
        List<LinkedProduct> products,
        Instant createdAt,
        Instant updatedAt
) {

    public record LinkedProduct(UUID productId, String title, SaleMode saleMode) {
    }
}
