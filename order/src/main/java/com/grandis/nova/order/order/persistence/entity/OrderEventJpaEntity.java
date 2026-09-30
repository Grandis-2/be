package com.grandis.nova.order.order.persistence.entity;

import com.grandis.nova.order.order.domain.enums.EventActor;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.time.Instant;

/** order_events 행. 추가 전용이라 {@link Immutable} 이다. */
@Entity
@Table(name = "order_events")
@IdClass(OrderEventJpaEntity.Key.class)
@Immutable
public class OrderEventJpaEntity {

    @Id
    private Long orderId;

    @Id
    private Long eventSequence;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EventActor actor;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    protected OrderEventJpaEntity() {
    }

    public OrderEventJpaEntity(Long orderId, long eventSequence, OrderStatus fromStatus, OrderStatus toStatus,
                               EventActor actor, String reason, Instant createdAt) {
        this.orderId = orderId;
        this.eventSequence = eventSequence;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getEventSequence() {
        return eventSequence;
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public EventActor getActor() {
        return actor;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 복합 키 (order_id, event_sequence). */
    public record Key(Long orderId, Long eventSequence) implements Serializable {
    }
}
