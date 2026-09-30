package com.grandis.nova.order.order.domain.repository;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;

/** 관리자 목록 조건. 값이 없으면 null 이고 그 조건은 걸지 않는다. */
public record AdminOrderFilter(OrderStatus status, OrderSource source) {

    public static final AdminOrderFilter NONE = new AdminOrderFilter(null, null);
}
