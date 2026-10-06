package com.grandis.nova.payment.outbox;

/**
 * payment 가 발행하는 메시지. 공통 아웃박스 메시지를 payment 의 종류 · aggregate 로 좁힌다.
 *
 * record 의 칸이 곧 payload 다. payload 에는 받는 쪽 계약에 있는 칸만 담는다 — 결제 키는 넣지 않는다.
 */
public sealed interface OutboxMessage extends com.grandis.nova.common.outbox.OutboxMessage permits OrderPaymentSettled, OrderRefundSettled {

    @Override
    OutboundEventType eventType();

    @Override
    AggregateType aggregateType();
}
