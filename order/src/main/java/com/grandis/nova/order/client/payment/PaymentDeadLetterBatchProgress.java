package com.grandis.nova.order.client.payment;

import java.time.Instant;
import java.util.UUID;

/** payment BatchRedriveProgress 와 같은 모양 — 일괄 되돌리기 진행. 칸 뜻은 그 record 가 정본이다. */
public record PaymentDeadLetterBatchProgress(UUID batchId, int targetCount, int skippedCount, int sent, int unsent,
                                             int succeeded, int failed, int canceled, int pending, Instant startedAt,
                                             Instant lastProgressAt, Instant finishedAt) {
}
