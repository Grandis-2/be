package com.grandis.nova.payment.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.grandis.nova.payment.domain.enums.DeclineReason;
import com.grandis.nova.payment.domain.enums.TargetType;
import com.grandis.nova.payment.domain.model.PaymentTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 주문 결제의 확정 결과(승인 · 거절). order 의 결과 소비기가 받아 주문을 전이한다 — 동기 응답을 잃어도 주문이 결과를 받는다.
 * 결과 불명 · 처리 중은 보내지 않는다(주문은 이미 결제 확인 중이다).
 *
 * payload: {providerOrderId, result, amount, approvedAt, declineReason}. 결제 키는 싣지 않는다.
 * providerOrderId 는 받는 쪽이 "지금 승인 중인 결제창의 결과인가" 를 가르는 데 쓴다 — 늦게 온 이전 결제창의 거절이
 * 새 결제창의 승인 중에 주문을 되돌리지 않게.
 *
 * @param orderId       주문 id(결제 대상 id). 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param amount        거래 금액(원). 받는 쪽은 주문 총액과 대조만 한다
 * @param approvedAt    승인일 때만. 결제사가 알린 승인 시각
 * @param declineReason 거절일 때만
 */
public record OrderPaymentSettled(@JsonIgnore Long orderId, String providerOrderId, Result result, BigDecimal amount,
                                  Instant approvedAt, DeclineReason declineReason) implements OutboxMessage {

    public OrderPaymentSettled {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(amount, "amount");
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new IllegalArgumentException("providerOrderId 가 없다: orderId=" + orderId);
        }
        if ((result == Result.APPROVED) != (approvedAt != null)
                || (result == Result.DECLINED) != (declineReason != null)) {
            throw new IllegalArgumentException("승인은 승인 시각만, 거절은 사유만 있다: result=" + result);
        }
    }

    public static OrderPaymentSettled approved(PaymentTransaction transaction, Instant approvedAt) {
        return new OrderPaymentSettled(orderIdOf(transaction), transaction.providerOrderId().value(), Result.APPROVED,
                transaction.amount().amount(), Objects.requireNonNull(approvedAt, "approvedAt"), null);
    }

    public static OrderPaymentSettled declined(PaymentTransaction transaction, DeclineReason reason) {
        return new OrderPaymentSettled(orderIdOf(transaction), transaction.providerOrderId().value(), Result.DECLINED,
                transaction.amount().amount(), null, Objects.requireNonNull(reason, "reason"));
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.ORDER_PAYMENT_SETTLED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.ORDER;
    }

    @Override
    public Long aggregateId() {
        return orderId;
    }

    private static Long orderIdOf(PaymentTransaction transaction) {
        if (transaction.target().type() != TargetType.ORDER) {
            throw new IllegalArgumentException("주문 결제가 아니다: " + transaction);
        }
        return transaction.target().id();
    }

    /** 받는 쪽(order)에 같은 이름의 enum 이 한 벌 더 있다. */
    public enum Result {
        APPROVED,
        DECLINED
    }
}
