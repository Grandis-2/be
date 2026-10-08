package com.grandis.nova.order.order.cancel;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * payment 가 알린 환불 확정 결과(결과 이벤트).
 *
 * @param amount 환불 금액(전액). 주문 총액과 대조만 한다(다르면 ERROR — 반영은 한다, 돈의 사실은 결제 쪽이다)
 */
public record RefundSettlement(UUID orderId, Result result, BigDecimal amount) {

    public RefundSettlement {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(amount, "amount");
    }

    /** 보내는 쪽(payment outbox.OrderRefundSettled.Result)과 이름이 같아야 한다. */
    public enum Result {
        REFUNDED,
        FAILED
    }
}
