package com.grandis.nova.order.order.persistence.entity;

import com.grandis.nova.common.BaseEntity;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * orders 행. persistence 밖으로 내보내지 않는다 — 밖에는 OrderMapper 가 도메인 주문으로 바꿔 내보낸다.
 *
 * 칼럼은 전부 updatable = false 다. 상태는 조건부 UPDATE(OrderJpaRepository.changeStatus)로만 바꾸므로,
 * 이 엔티티를 읽어 둔 트랜잭션이 커밋할 때 변경 감지가 옛 값을 덮어쓰는 일을 칼럼 수준에서 막아 둔다.
 */
@Entity
@Table(name = "orders")
public class OrderJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 36)
    private String orderToken;

    @Column(nullable = false, updatable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private OrderSource source;

    @Column(updatable = false)
    private Long preorderId;

    @Column(updatable = false, length = 36)
    private String preorderToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private OrderStatus status;

    /** 상태와 함께 원장의 벌크 UPDATE 로만 바뀐다(ck_order_authorizing_attempt). 새 주문은 늘 비어 있다. */
    @Column(updatable = false, length = 64)
    private String authorizingProviderOrderId;

    @Column(nullable = false, updatable = false, precision = 12, scale = 0)
    private BigDecimal totalAmount;

    @Column(updatable = false)
    private Instant paymentDueAt;

    @Column(updatable = false)
    private Instant stockReleasedAt;

    @Column(name = "ship_to_name", nullable = false, updatable = false, length = 50)
    private String shipToName;

    @Column(name = "ship_to_phone", nullable = false, updatable = false, length = 20)
    private String shipToPhone;

    @Column(name = "ship_to_postal_code", nullable = false, updatable = false, length = 10)
    private String shipToPostalCode;

    @Column(name = "ship_to_line1", nullable = false, updatable = false, length = 200)
    private String shipToLine1;

    @Column(name = "ship_to_line2", updatable = false, length = 200)
    private String shipToLine2;

    @Column(updatable = false, columnDefinition = "text")
    private String internalNote;

    @Column(nullable = false, updatable = false)
    private long eventSequence;

    protected OrderJpaEntity() {
    }

    public OrderJpaEntity(String orderToken, Long customerId, OrderSource source, Long preorderId, String preorderToken,
                          OrderStatus status, BigDecimal totalAmount, Instant paymentDueAt, Instant stockReleasedAt,
                          String shipToName, String shipToPhone, String shipToPostalCode, String shipToLine1,
                          String shipToLine2, String internalNote, long eventSequence) {
        this.orderToken = orderToken;
        this.customerId = customerId;
        this.source = source;
        this.preorderId = preorderId;
        this.preorderToken = preorderToken;
        this.status = status;
        this.totalAmount = totalAmount;
        this.paymentDueAt = paymentDueAt;
        this.stockReleasedAt = stockReleasedAt;
        this.shipToName = shipToName;
        this.shipToPhone = shipToPhone;
        this.shipToPostalCode = shipToPostalCode;
        this.shipToLine1 = shipToLine1;
        this.shipToLine2 = shipToLine2;
        this.internalNote = internalNote;
        this.eventSequence = eventSequence;
    }

    public Long getId() {
        return id;
    }

    public String getOrderToken() {
        return orderToken;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public OrderSource getSource() {
        return source;
    }

    public Long getPreorderId() {
        return preorderId;
    }

    public String getPreorderToken() {
        return preorderToken;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getAuthorizingProviderOrderId() {
        return authorizingProviderOrderId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Instant getPaymentDueAt() {
        return paymentDueAt;
    }

    public Instant getStockReleasedAt() {
        return stockReleasedAt;
    }

    public String getShipToName() {
        return shipToName;
    }

    public String getShipToPhone() {
        return shipToPhone;
    }

    public String getShipToPostalCode() {
        return shipToPostalCode;
    }

    public String getShipToLine1() {
        return shipToLine1;
    }

    public String getShipToLine2() {
        return shipToLine2;
    }

    public String getInternalNote() {
        return internalNote;
    }

    public long getEventSequence() {
        return eventSequence;
    }
}
