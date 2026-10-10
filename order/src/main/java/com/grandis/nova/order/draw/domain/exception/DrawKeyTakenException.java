package com.grandis.nova.order.draw.domain.exception;

/** 같은 Idempotency-Key 의 회차를 다른 요청이 먼저 넣었다(uq_draw_campaign_idempotency). 그 트랜잭션은 롤백하고 먼저 들어간 회차를 읽는다. */
public class DrawKeyTakenException extends RuntimeException {

    public DrawKeyTakenException(Throwable cause) {
        super("draw idempotency key already taken", cause);
    }
}
