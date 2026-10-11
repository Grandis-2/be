package com.grandis.nova.order.order.domain.repository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 기한이 지난 주문을 (기한, id) 순으로 이어 읽는 자리 — 그 주문 다음부터 읽는다. */
public record ExpiredOrderPosition(Instant paymentDueAt, UUID id) {

    /** 처음 — 어떤 기한 · id 보다 앞이다(id 는 바이트가 모두 0). */
    public static final ExpiredOrderPosition START = new ExpiredOrderPosition(Instant.EPOCH, new UUID(0, 0));

    public ExpiredOrderPosition {
        Objects.requireNonNull(paymentDueAt, "paymentDueAt");
        Objects.requireNonNull(id, "id");
    }
}
