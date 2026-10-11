package com.grandis.nova.order.cart.api;

import com.grandis.nova.order.cart.domain.model.CartLine;

import java.util.UUID;

/** 담기 · 수량 변경의 응답 — 그 줄의 지금 수량. */
public record CartItemResponse(UUID cartItemId, UUID variantId, boolean warranty, int quantity) {

    static CartItemResponse from(CartLine line) {
        return new CartItemResponse(line.id(), line.optionId(), line.warranty(), line.quantity());
    }
}
