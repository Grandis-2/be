package com.grandis.nova.order.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 사전예약 주문의 결제 승인을 반영했다 — 예약 확정. preorder 의 {@code event.PreorderPaymentConfirmed} 가 받는다.
 * 승인 반영과 같은 트랜잭션에 적는다 — 반영이 되돌아가면 알림도 없고, 승인은 원장이 한 번만 반영하므로 알림도 한 번이다.
 *
 * payload: {preorderId, orderId, paidAt}.
 *
 * @param preorderInternalId 예약 내부 id. 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param preorderId         공개 UUID(preorder_token). 받는 쪽이 이것으로 예약을 찾는다
 * @param paidAt             주문의 결제 시각(orders.paid_at)
 */
public record PreorderPaymentConfirmed(@JsonIgnore UUID preorderInternalId, String preorderId, UUID orderId,
                                       Instant paidAt) implements OutboxMessage {

    public PreorderPaymentConfirmed {
        Objects.requireNonNull(preorderInternalId, "preorderInternalId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(paidAt, "paidAt");
        if (preorderId == null || preorderId.isBlank()) {
            throw new IllegalArgumentException("preorderId 가 없다: orderId=" + orderId);
        }
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.PREORDER_PAYMENT_CONFIRMED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.PREORDER;
    }

    @Override
    public UUID aggregateId() {
        return preorderInternalId;
    }
}
