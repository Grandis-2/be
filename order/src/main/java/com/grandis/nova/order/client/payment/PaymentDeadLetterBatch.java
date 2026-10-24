package com.grandis.nova.order.client.payment;

/** payment BatchRedrive 와 같은 모양 — 일괄 되돌리기 시작 결과. 대상은 payment 가 초당 정해진 건수로 이어서 보낸다. */
public record PaymentDeadLetterBatch(int targetCount, int skippedCount, long estimatedSeconds) {
}
