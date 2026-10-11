package com.grandis.nova.catalog.review;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** PATCH /api/v1/reviews/{id} 본문. null 은 "보내지 않음" 이고 그 칸은 그대로다. 둘 다 null 이면 400. */
public record ReviewUpdateRequest(
        @Min(ProductReview.MIN_RATING) @Max(ProductReview.MAX_RATING) Integer rating,
        String body
) {

    @Schema(hidden = true)   // 문서 전용 — 없으면 swagger 가 getter 꼴 메서드를 empty 칸으로 그린다
    public boolean isEmpty() {
        return rating == null && body == null;
    }
}
