package com.grandis.nova.order.order.persistence.mapper;

import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderEvent;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.persistence.entity.OrderEventJpaEntity;
import com.grandis.nova.order.order.persistence.entity.OrderItemJpaEntity;
import com.grandis.nova.order.order.persistence.entity.OrderJpaEntity;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.OrderToken;
import com.grandis.nova.order.order.vo.Quantity;
import com.grandis.nova.order.order.vo.ShipTo;

/** 도메인 ↔ JPA 엔티티 변환. 엔티티가 persistence 밖으로 새지 않게 어댑터({@code JpaOrderStore})만 쓴다. */
public final class OrderMapper {

    private OrderMapper() {
    }

    public static OrderJpaEntity toEntity(Order order) {
        ShipTo shipTo = order.shipTo();
        return new OrderJpaEntity(order.orderToken().value(), order.customerId(), order.source(),
                order.preorderId(), order.preorderToken(), order.status(), order.totalAmount().amount(), order.paymentDueAt(),
                order.stockReleasedAt(), shipTo.name(), shipTo.phone(), shipTo.postalCode(), shipTo.line1(),
                shipTo.line2(), order.internalNote(), order.eventSequence());
    }

    public static Order toDomain(OrderJpaEntity entity) {
        return new Order(entity.getId(), new OrderToken(entity.getOrderToken()), entity.getCustomerId(),
                entity.getSource(), entity.getPreorderId(), entity.getPreorderToken(), entity.getStatus(), new Money(entity.getTotalAmount()),
                entity.getPaymentDueAt(), entity.getStockReleasedAt(),
                new ShipTo(entity.getShipToName(), entity.getShipToPhone(), entity.getShipToPostalCode(),
                        entity.getShipToLine1(), entity.getShipToLine2()),
                entity.getInternalNote(), entity.getEventSequence(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    public static OrderItemJpaEntity toEntity(Long orderId, OrderLine line) {
        return new OrderItemJpaEntity(orderId, line.productId(), line.optionId(), line.quantity().value(),
                line.unitPrice().amount(), line.productTitle(), line.optionTitle());
    }

    public static OrderItem toDomain(OrderItemJpaEntity entity) {
        return new OrderItem(entity.getId(), entity.getOrderId(), new OrderLine(entity.getProductId(),
                entity.getOptionId(), new Quantity(entity.getQuantity()), new Money(entity.getUnitPriceSnapshot()),
                entity.getProductTitleSnapshot(), entity.getOptionTitleSnapshot()));
    }

    public static OrderEventJpaEntity toEntity(OrderEvent event) {
        return new OrderEventJpaEntity(event.orderId(), event.eventSequence(), event.fromStatus(), event.toStatus(),
                event.cause().actor(), event.cause().reason(), event.createdAt());
    }

    public static OrderEvent toDomain(OrderEventJpaEntity entity) {
        return new OrderEvent(entity.getOrderId(), entity.getEventSequence(), entity.getFromStatus(),
                entity.getToStatus(), new EventCause(entity.getActor(), entity.getReason()), entity.getCreatedAt());
    }
}
