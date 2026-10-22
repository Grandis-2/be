package com.grandis.nova.order.order.api;

import com.grandis.nova.common.CursorPage;
import com.grandis.nova.order.order.domain.enums.OrderStatus;

import java.util.List;
import java.util.Map;

/**
 * 내 주문 목록(GET /api/v1/orders). 페이지 칸(items · nextCursor)은 다른 커서 목록과 같고, 상태별 건수를 더한다.
 *
 * @param nextCursor 다음 페이지를 읽을 커서. 마지막 페이지면 null
 * @param counts     상태별 주문 수 — 목록의 조건(status · from)과 상관없이 내 주문 전부. 모든 상태가 상태 순서로 들어 있고, 주문이 없는 상태는 0
 */
public record MyOrdersResponse(List<OrderResponse> items, String nextCursor, Map<OrderStatus, Long> counts) {

    static MyOrdersResponse of(CursorPage<OrderResponse> page, Map<OrderStatus, Long> counts) {
        return new MyOrdersResponse(page.items(), page.nextCursor(), counts);
    }
}
