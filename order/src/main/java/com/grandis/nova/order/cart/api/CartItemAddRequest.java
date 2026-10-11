package com.grandis.nova.order.cart.api;

import com.grandis.nova.order.cart.domain.model.CartLimits;
import com.grandis.nova.order.web.WholeInteger;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.UUID;

/**
 * POST /api/v1/cart/items.
 *
 * @param variantId 담을 옵션(product_options.id)
 * @param quantity  1~99 정수(소수 · 문자열은 400 — 잘라 받지 않는다). 같은 (옵션, 보증)이 있으면 그 줄에 더하고, 더한 값도 99 이하여야 한다
 * @param warranty  보증(AppleCare 등)을 함께 사는가. 빠지면 false. 같은 옵션이라도 보증 포함 · 미포함은 다른 줄이다
 */
public record CartItemAddRequest(
        @NotNull UUID variantId,
        @JsonDeserialize(using = WholeInteger.class) @NotNull @Min(1) @Max(CartLimits.MAX_QUANTITY) Integer quantity,
        Boolean warranty
) {
    public boolean warrantySelected() {
        return Boolean.TRUE.equals(warranty);
    }
}
