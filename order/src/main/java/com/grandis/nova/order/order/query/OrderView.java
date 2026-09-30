package com.grandis.nova.order.order.query;

import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderEvent;
import com.grandis.nova.order.order.domain.model.OrderItem;

import java.util.List;

/** 조회 결과. 주문은 항목 · 이력을 들고 있지 않아(id 로 잇는다) 화면이 필요로 하는 것을 여기서 묶는다. */
public final class OrderView {

    private OrderView() {
    }

    /** 목록 한 줄. */
    public record Summary(Order order, List<OrderItem> items) {
    }

    /** 상세. 이력은 번호순이고 주문을 읽은 시점의 번호까지다. */
    public record Detail(Order order, List<OrderItem> items, List<OrderEvent> events) {
    }
}
