package com.grandis.nova.order.draw.domain.exception;

/** 그 회차에 그 회원의 응모가 이미 있다(uq_draw_entry_customer). 동시에 응모하면 한쪽이 받는다. */
public class DrawEntryTakenException extends RuntimeException {

    public DrawEntryTakenException(Throwable cause) {
        super("draw entry already taken", cause);
    }
}
