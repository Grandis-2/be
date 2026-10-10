package com.grandis.nova.order.cart.domain.exception;

/**
 * 새 줄로 넣으려던 (회원, 옵션, 보증)을 다른 트랜잭션이 먼저 넣었다 — 비어 있던 장바구니에 같은 옵션 담기가 겹친 경우다
 * (줄이 하나라도 있으면 담기는 그 회원의 줄을 모두 잠근 뒤라 겹치지 않는다). 새 트랜잭션에서 다시 하면 그 줄에 합산된다.
 */
public class CartSlotTakenException extends RuntimeException {

    public CartSlotTakenException(Throwable cause) {
        super("cart slot already taken", cause);
    }
}
