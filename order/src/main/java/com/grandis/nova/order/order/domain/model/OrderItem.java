package com.grandis.nova.order.order.domain.model;

import java.util.Objects;

/** 저장된 주문상품. 부분 취소가 없어 항목별 상태가 없고 불변이다. */
public record OrderItem(Long id, Long orderId, OrderLine line) {

    public OrderItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(line, "line");
    }
}
