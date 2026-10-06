package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 주문 응답. 생성 · 조회가 함께 쓴다.
 *
 * 밖에는 공개 토큰(orderId)만 알린다 — 내부 id · 예약 내부 id 는 싣지 않는다.
 * 결제 기한도 싣지 않는다. 사전예약 주문의 기한은 preorder 상세가 계산해 준다.
 *
 * @param orderId order_token
 */
public record OrderResponse(
        String orderId,
        OrderStatus status,
        OrderSource source,
        BigDecimal totalAmount,
        List<Item> items,
        ShipTo shipTo,
        Instant createdAt
) {

    public static OrderResponse of(Order order, List<OrderItem> items) {
        com.grandis.nova.order.order.vo.ShipTo shipTo = order.shipTo();
        return new OrderResponse(order.orderToken().value(), order.status(), order.source(),
                order.totalAmount().amount(), items.stream().map(item -> Item.of(item.line())).toList(),
                new ShipTo(shipTo.name(), shipTo.phone(), shipTo.postalCode(), shipTo.line1(), shipTo.line2()),
                order.createdAt());
    }

    /** 주문상품. 이름 · 단가는 예약 접수 시점의 스냅샷이다. */
    public record Item(Long productId, Long optionId, String productTitle, String optionTitle,
                       BigDecimal unitPrice, int quantity) {

        static Item of(OrderLine line) {
            return new Item(line.productId(), line.optionId(), line.productTitle(), line.optionTitle(),
                    line.unitPrice().amount(), line.quantity().value());
        }
    }

    /** 주문에 복사해 둔 배송지. 본인 · 관리자에게만 나가는 응답이다. line2 는 없으면 null. */
    public record ShipTo(String name, String phone, String postalCode, String line1, String line2) {
    }
}
