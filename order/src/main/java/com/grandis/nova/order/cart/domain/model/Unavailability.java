package com.grandis.nova.order.cart.domain.model;

/**
 * 장바구니 줄을 지금 살 수 없는 이유. 여럿이 겹치면 위에 있는 것 하나만 보인다 — 사용자가 먼저 고쳐야 할 것부터.
 */
public enum Unavailability {
    /** 옵션이 없어졌다(catalog 가 돌려주지 않았다). */
    DISCONTINUED,
    /** 회원에게 보이지 않는 상품이다(비공개 · 판매 준비 전 · 사전예약). */
    HIDDEN,
    /** 상품 또는 옵션이 판매 중지다. */
    PAUSED,
    /** 보증을 골랐는데 상품이 더는 보증을 주지 않는다. */
    WARRANTY_UNAVAILABLE,
    /** 가용 재고가 없다(재고 행이 없거나 0). */
    OUT_OF_STOCK,
    /** 가용 재고보다 많이 담았다. */
    INSUFFICIENT_STOCK;

    /**
     * 판정 한 곳. option 이 null 이면 판매 종료. 살 수 있으면 null.
     *
     * @param available 그 옵션의 가용 재고(총량 − 확보 − 판매). 재고 행이 없으면 0
     */
    public static Unavailability of(CartOption option, boolean warranty, int quantity, int available) {
        if (option == null) {
            return DISCONTINUED;
        }
        if (!option.exposed() || !option.inStock()) {
            return HIDDEN;
        }
        if (!option.sellableStatus()) {
            return PAUSED;
        }
        if (warranty && !option.warrantyOffered()) {
            return WARRANTY_UNAVAILABLE;
        }
        if (available <= 0) {
            return OUT_OF_STOCK;
        }
        if (quantity > available) {
            return INSUFFICIENT_STOCK;
        }
        return null;
    }
}
