package com.grandis.nova.catalog.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** POST /api/v1/reviews 본문. 본문 길이는 앞뒤 공백을 뺀 뒤 서비스가 잰다(2,000자). */
public record ReviewCreateRequest(
        @NotNull @Positive Long orderItemId,
        @NotNull @Min(ProductReview.MIN_RATING) @Max(ProductReview.MAX_RATING) Integer rating,
        @NotBlank String body
) {
}
