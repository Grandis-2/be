package com.grandis.nova.order.event;

import com.grandis.nova.order.order.cancel.RefundSettlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * ORDER_REFUND_SETTLED 의 payload(payment outbox.OrderRefundSettled 가 보낸다). 주문 id 는 payload 에 없고 봉투의 aggregateId 로만 온다.
 * 결제 키는 오지 않는다.
 *
 * 칸이 빠지거나 결과와 맞지 않으면 풀 때 실패시킨다(재수신 한도를 넘으면 DLQ).
 *
 * @param refundedAt 완료일 때만. 주문은 저장하지 않는다
 */
public record OrderRefundSettled(RefundSettlement.Result result, BigDecimal amount, Instant refundedAt) {

    public OrderRefundSettled {
        if (result == null || amount == null || (result == RefundSettlement.Result.REFUNDED) != (refundedAt != null)) {
            throw new IllegalArgumentException("환불 결과에 빠지거나 맞지 않는 칸이 있다: result=" + result);
        }
    }

    RefundSettlement toSettlement(UUID orderId) {
        return new RefundSettlement(orderId, result, amount);
    }
}
