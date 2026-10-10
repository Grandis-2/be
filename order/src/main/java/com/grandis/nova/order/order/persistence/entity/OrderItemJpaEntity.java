package com.grandis.nova.order.order.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

/** order_items 행. 모든 칼럼이 불변이다. 시각 칼럼이 없는 표라 BaseEntity 대신 id(UUID v7)만 스스로 둔다. */
@Entity
@Table(name = "order_items")
public class OrderItemJpaEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private UUID optionId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(nullable = false, updatable = false, precision = 12, scale = 0)
    private BigDecimal unitPriceSnapshot;

    @Column(nullable = false, updatable = false)
    private int warrantyQuantity;

    @Column(nullable = false, updatable = false, precision = 12, scale = 0)
    private BigDecimal warrantyPriceSnapshot;

    @Column(nullable = false, updatable = false, length = 100)
    private String productTitleSnapshot;

    @Column(nullable = false, updatable = false, length = 120)
    private String optionTitleSnapshot;

    protected OrderItemJpaEntity() {
    }

    public OrderItemJpaEntity(UUID orderId, UUID productId, UUID optionId, int quantity, BigDecimal unitPriceSnapshot,
                              int warrantyQuantity, BigDecimal warrantyPriceSnapshot,
                              String productTitleSnapshot, String optionTitleSnapshot) {
        this.orderId = orderId;
        this.productId = productId;
        this.optionId = optionId;
        this.quantity = quantity;
        this.unitPriceSnapshot = unitPriceSnapshot;
        this.warrantyQuantity = warrantyQuantity;
        this.warrantyPriceSnapshot = warrantyPriceSnapshot;
        this.productTitleSnapshot = productTitleSnapshot;
        this.optionTitleSnapshot = optionTitleSnapshot;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getOptionId() {
        return optionId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPriceSnapshot() {
        return unitPriceSnapshot;
    }

    public int getWarrantyQuantity() {
        return warrantyQuantity;
    }

    public BigDecimal getWarrantyPriceSnapshot() {
        return warrantyPriceSnapshot;
    }

    public String getProductTitleSnapshot() {
        return productTitleSnapshot;
    }

    public String getOptionTitleSnapshot() {
        return optionTitleSnapshot;
    }
}
