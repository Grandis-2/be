package com.grandis.nova.order.order.domain.exception;

/**
 * 같은 회원의 같은 멱등 키로 이미 주문이 있다(uq_order_idempotency). 저장소가 벤더 코드 · 제약 이름을 보고 이 예외로 바꾼다.
 * 이 예외가 나면 호출자의 트랜잭션(재고 확보 포함)은 rollback-only 다. 먼저 들어간 주문은 새 트랜잭션에서 읽는다.
 */
public class OrderKeyTakenException extends RuntimeException {

    public OrderKeyTakenException(Throwable cause) {
        super("order idempotency key already taken", cause);
    }
}
