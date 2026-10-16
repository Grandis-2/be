package com.grandis.nova.order.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 사전예약 주문을 만들었다(결제 시작) — 예약 화면의 "결제 진행 중" 단계. preorder 의 {@code event.PreorderPaymentStarted} 가 받는다.
 * 주문 생성과 같은 트랜잭션에 적는다. 기존 주문을 돌려준 재요청에는 적지 않는다(contracts/preorder-order-events.md).
 *
 * payload: {preorderId, orderId, startedAt}.
 *
 * @param preorderInternalId 예약 내부 id. 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param preorderId         공개 UUID(preorder_token). 받는 쪽이 이것으로 예약을 찾는다
 * @param startedAt          주문을 만든 시각
 */
public record PreorderPaymentStarted(@JsonIgnore UUID preorderInternalId, String preorderId, UUID orderId,
                                     Instant startedAt) implements OutboxMessage {

    public PreorderPaymentStarted {
        Objects.requireNonNull(preorderInternalId, "preorderInternalId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(startedAt, "startedAt");
        if (preorderId == null || preorderId.isBlank()) {
            throw new IllegalArgumentException("preorderId 가 없다: orderId=" + orderId);
        }
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.PREORDER_PAYMENT_STARTED;
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
