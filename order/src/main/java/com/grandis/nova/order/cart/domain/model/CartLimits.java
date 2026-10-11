package com.grandis.nova.order.cart.domain.model;

/**
 * 장바구니 상한 — 한 줄 수량 1~99(ck_cart_quantity 와 같다), 줄 수 50(앱이 지킨다 — 담기 트랜잭션이 그 회원의 줄을 모두 잠근 뒤 센다).
 * 재고 상한과 별개다.
 */
public final class CartLimits {

    public static final int MAX_QUANTITY = 99;
    public static final int MAX_LINES = 50;

    private CartLimits() {
    }

    public static void requireQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("cart quantity must be 1.." + MAX_QUANTITY + ": " + quantity);
        }
    }
}
