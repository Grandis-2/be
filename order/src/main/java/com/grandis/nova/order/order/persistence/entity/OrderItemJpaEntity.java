package com.grandis.nova.order.order.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** order_items 행. 모든 칼럼이 불변이다. 시각 칼럼이 없는 테이블이라 BaseEntity 를 쓰지 않는다. */
@Entity
@Table(name = "order_items")
public class OrderItemJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long orderId;

    @Column(nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false)
    private Long optionId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(nullable = false, updatable = false, precision = 12, scale = 0)
    private BigDecimal unitPriceSnapshot;

    @Column(nullable = false, updatable = false, length = 100)
    private String productTitleSnapshot;

    @Column(nullable = false, updatable = false, length = 120)
    private String optionTitleSnapshot;

    protected OrderItemJpaEntity() {
    }

    public OrderItemJpaEntity(Long orderId, Long productId, Long optionId, int quantity, BigDecimal unitPriceSnapshot,
                              String productTitleSnapshot, String optionTitleSnapshot) {
        this.orderId = orderId;
        this.productId = productId;
        this.optionId = optionId;
        this.quantity = quantity;
        this.unitPriceSnapshot = unitPriceSnapshot;
        this.productTitleSnapshot = productTitleSnapshot;
        this.optionTitleSnapshot = optionTitleSnapshot;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getOptionId() {
        return optionId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPriceSnapshot() {
        return unitPriceSnapshot;
    }

    public String getProductTitleSnapshot() {
        return productTitleSnapshot;
    }

    public String getOptionTitleSnapshot() {
        return optionTitleSnapshot;
    }
}
