package com.grandis.nova.order.order.domain.repository;

import com.grandis.nova.order.order.domain.enums.OrderStatus;

import java.time.Instant;
import java.util.Set;

/**
 * 내 주문 목록 조건. 조건끼리는 AND 다.
 *
 * @param statuses    이 상태들 중 하나인 주문. 비었으면 거르지 않는다
 * @param createdFrom 주문 시각 이 시각 이상. null 이면 거르지 않는다
 */
public record CustomerOrderFilter(Set<OrderStatus> statuses, Instant createdFrom) {

    public static final CustomerOrderFilter NONE = new CustomerOrderFilter(Set.of(), null);

    public CustomerOrderFilter {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
    }
}
