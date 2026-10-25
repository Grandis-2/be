package com.grandis.nova.order.client.payment;

import java.util.UUID;

/** payment BatchRedrive 와 같은 모양 — 일괄 되돌리기 시작 결과. 대상은 payment 가 초당 정해진 건수로 이어서 보내고, 진행은 batchId 로 본다. */
public record PaymentDeadLetterBatch(UUID batchId, int targetCount, int skippedCount, long estimatedSeconds) {
}
