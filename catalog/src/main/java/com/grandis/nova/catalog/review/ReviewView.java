package com.grandis.nova.catalog.review;

import java.time.Instant;

/**
 * 리뷰 한 건 — 상품 상세 후기 탭 · 모아보기 · 내 리뷰가 같은 모양을 쓴다.
 *
 * @param productTitle 지금의 상품명(catalog 표)
 * @param optionTitle  구매 당시 옵션명(작성 때 order 에서 받아 둔 값)
 * @param imageUrl     상품 대표 사진. 없으면 null
 * @param authorName   가린 작성자명(예: 김**)
 * @param orderItemId  내 리뷰에서만 채운다. 공개 목록은 null — 남의 주문상품 id 를 내보내지 않는다
 */
public record ReviewView(
        Long reviewId,
        Long productId,
        String productTitle,
        String optionTitle,
        String imageUrl,
        int rating,
        String body,
        String authorName,
        Instant createdAt,
        Instant updatedAt,
        Long orderItemId
) {
}
