package com.grandis.nova.order.order.place;

import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderItem;

import java.util.List;

/**
 * 주문 생성 결과.
 *
 * @param created 이번 요청이 만들었으면 true, 같은 예약의 기존 주문이면 false
 */
public record PlaceResult(Order order, List<OrderItem> items, boolean created) {

    public PlaceResult {
        items = List.copyOf(items);
    }
}
