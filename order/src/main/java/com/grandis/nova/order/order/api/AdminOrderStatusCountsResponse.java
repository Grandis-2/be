package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.repository.PaymentUnconfirmedCounts;
import com.grandis.nova.order.order.query.OrderStatusCounts;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * 상태별 주문 수.
 *
 * @param byStatus           모든 상태가 상태 순서로 들어 있다. 주문이 없는 상태는 0
 * @param paymentUnconfirmed 결제 결과를 끝내 몰라 취소한 주문 수(countedAt 기준 최근 1시간 · 24시간). 상품별 수에서는 null
 * @param countedAt          센 시각. 전체 수는 캐시라 지금보다 최대 5초 이르다
 */
public record AdminOrderStatusCountsResponse(Map<OrderStatus, Long> byStatus,
                                             @Schema(nullable = true) PaymentUnconfirmedCounts paymentUnconfirmed,
                                             Instant countedAt) {

    static AdminOrderStatusCountsResponse of(OrderStatusCounts.Snapshot snapshot) {
        return new AdminOrderStatusCountsResponse(snapshot.byStatus(), snapshot.paymentUnconfirmed(), snapshot.countedAt());
    }
}
