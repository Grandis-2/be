package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.product.Discount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * POST /admin/products/{id}/conversion 본문 — 마감된 사전예약 상품을 일반 상품으로 바꾼다.
 *
 * @param discountRate 일반 판매의 할인율(정수 %, 0~99). 비우면 0 — 사전예약 때의 할인율을 이어 쓰지 않는다
 * @param stocks       옵션마다 초기 재고. 그 상품의 옵션(판매 중지 포함)을 하나도 빠짐없이 한 번씩, 0 허용
 */
public record InStockConversionRequest(
        @Min(Discount.MIN_RATE) @Max(Discount.MAX_RATE) Integer discountRate,
        @NotNull @Size(min = 1, max = MAX_OPTIONS) List<@NotNull @Valid Stock> stocks
) {

    /** 상품당 조합 상한과 같다(ProductRegistrationRequest.MAX_COMBINATIONS · order 의 재고 요청 상한). */
    public static final int MAX_OPTIONS = 500;

    public InStockConversionRequest {
        discountRate = discountRate == null ? 0 : discountRate;
    }

    @Schema(name = "ConversionStock")
    public record Stock(@NotNull UUID variantId, @NotNull @PositiveOrZero Integer stock) {
    }
}
