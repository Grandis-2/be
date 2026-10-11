package com.grandis.nova.order.cart.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 장바구니 한 줄(cart_items 한 행). 같은 옵션이라도 보증 포함 · 미포함이면 다른 줄이다(유일 키 회원 · 옵션 · 보증).
 * 가격은 담지 않는다 — 조회 때 catalog 의 지금 값을 쓴다.
 */
public record CartLine(UUID id, UUID customerId, UUID optionId, boolean warranty, int quantity) {

    public CartLine {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(optionId, "optionId");
        CartLimits.requireQuantity(quantity);
    }

    public CartSlot slot() {
        return new CartSlot(optionId, warranty);
    }

    /** 같은 (옵션, 보증)의 줄인가 — 담기가 이 줄에 합산된다. */
    public boolean sameSlot(UUID otherOptionId, boolean otherWarranty) {
        return optionId.equals(otherOptionId) && warranty == otherWarranty;
    }
}
