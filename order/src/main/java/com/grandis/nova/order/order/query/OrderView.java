package com.grandis.nova.order.order.query;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderEvent;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.vo.EventCause;

import java.util.List;

/** 조회 결과. 주문은 항목 · 이력을 들고 있지 않아(id 로 잇는다) 화면이 필요로 하는 것을 여기서 묶는다. */
public final class OrderView {

    private OrderView() {
    }

    /** 주문상품 하나와 그 주문. */
    public record Item(Order order, OrderItem item) {
    }

    /** 목록 한 줄. */
    public record Summary(Order order, List<OrderItem> items) {
    }

    /** 상세. 이력은 번호순이고 주문을 읽은 시점의 번호까지다. */
    public record Detail(Order order, List<OrderItem> items, List<OrderEvent> events) {

        /**
         * 환불이 확정 실패해 돈이 돌아가지 않은 채다. 사용자에겐 실패 사실만 보인다(U1). 취소 중 주문은 환불 실패 시각으로 본다.
         * 결과를 모른 채 취소한 주문의 자동 환불은 주문이 이미 취소라, 실패 시각이 있고 마지막 이력이 환불 실패인지로 본다. 사전예약 취소의
         * 실패를 사람이 해소하면 취소 중 → 취소 이력이 붙어 실패로 보지 않는다. 자동 환불의 확정 실패는 지금 다시 열리지 않는다(payment 결정
         * 33) — 다시 여는 길을 더하면 그 해소도 이력을 남겨야 한다.
         */
        public boolean refundFailed() {
            if (order.refundFailedAt() == null) {
                return false;
            }
            if (order.status() == OrderStatus.CANCELING) {
                return true;
            }
            return order.status() == OrderStatus.CANCELED && !events.isEmpty()
                    && EventCause.REFUND_FAILED.equals(events.get(events.size() - 1).cause().reason());
        }
    }
}
