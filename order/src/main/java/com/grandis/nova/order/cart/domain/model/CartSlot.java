package com.grandis.nova.order.cart.domain.model;

import java.util.Objects;
import java.util.UUID;

/** 장바구니 줄의 자리 — (옵션, 보증). 한 회원에게 자리마다 줄은 하나다(uq_cart_customer_option_warranty). */
public record CartSlot(UUID optionId, boolean warranty) {

    public CartSlot {
        Objects.requireNonNull(optionId, "optionId");
    }
}
