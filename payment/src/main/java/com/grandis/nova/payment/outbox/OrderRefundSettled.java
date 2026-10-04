package com.grandis.nova.payment.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.grandis.nova.payment.domain.enums.TargetType;
import com.grandis.nova.payment.domain.model.PaymentTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 주문 결제의 환불 확정 결과(완료 · 실패). order 의 결과 소비기가 받아 주문을 취소로 끝내거나(완료) 취소 중에 둔 채 환불 실패를 표시한다.
 * 결과 불명 · 처리 중 · 에스컬레이션은 보내지 않는다(주문은 이미 취소 중이다).
 *
 * payload: {result, amount, refundedAt}. 결제 키는 싣지 않는다.
 *
 * @param orderId    주문 id(결제 대상 id). 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param amount     환불 금액(원, 전액). 받는 쪽은 주문 총액과 대조만 한다
 * @param refundedAt 완료일 때만. 결제사가 알린 취소 시각
 */
public record OrderRefundSettled(@JsonIgnore Long orderId, Result result, BigDecimal amount, Instant refundedAt)
        implements OutboxMessage {

    public OrderRefundSettled {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(amount, "amount");
        if ((result == Result.REFUNDED) != (refundedAt != null)) {
            throw new IllegalArgumentException("완료만 환불 시각이 있다: result=" + result);
        }
    }

    public static OrderRefundSettled refunded(PaymentTransaction refund, Instant refundedAt) {
        return new OrderRefundSettled(orderIdOf(refund), Result.REFUNDED, refund.amount().amount(),
                Objects.requireNonNull(refundedAt, "refundedAt"));
    }

    public static OrderRefundSettled failed(PaymentTransaction refund) {
        return new OrderRefundSettled(orderIdOf(refund), Result.FAILED, refund.amount().amount(), null);
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.ORDER_REFUND_SETTLED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.ORDER;
    }

    @Override
    public Long aggregateId() {
        return orderId;
    }

    private static Long orderIdOf(PaymentTransaction refund) {
        if (refund.target().type() != TargetType.ORDER) {
            throw new IllegalArgumentException("주문 결제의 환불이 아니다: " + refund);
        }
        return refund.target().id();
    }

    /** 받는 쪽(order)에 같은 이름의 enum 이 한 벌 더 있다. */
    public enum Result {
        REFUNDED,
        FAILED
    }
}
