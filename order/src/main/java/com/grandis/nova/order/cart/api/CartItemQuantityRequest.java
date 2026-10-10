package com.grandis.nova.order.cart.api;

import com.grandis.nova.order.cart.domain.model.CartLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** PATCH /api/v1/cart/items/{cartItemId}. 수량 절대값(1~99)만 — 옵션 · 보증 교체는 삭제 후 담기다. */
public record CartItemQuantityRequest(@NotNull @Min(1) @Max(CartLimits.MAX_QUANTITY) Integer quantity) {
}
