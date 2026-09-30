package com.grandis.nova.order.order.domain.repository;

import java.time.Instant;
import java.util.Objects;

/**
 * 목록에서 마지막으로 본 주문의 자리. 정렬이 (created_at, id) 내림차순이라 둘을 함께 들고 다닌다 —
 * 시각 하나만 쓰면 같은 시각의 주문이 페이지 경계에서 새거나 겹친다.
 */
public record OrderPosition(Instant createdAt, Long id) {

    public OrderPosition {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(id, "id");
    }
}
