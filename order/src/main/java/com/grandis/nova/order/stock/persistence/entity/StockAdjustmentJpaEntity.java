package com.grandis.nova.order.stock.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** stock_adjustments 행 — 관리자 보정 기록. 넣기만 한다(모든 칼럼이 불변). */
@Entity
@Table(name = "stock_adjustments")
public class StockAdjustmentJpaEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID optionId;

    @Column(nullable = false, updatable = false)
    private int beforeReserved;

    @Column(nullable = false, updatable = false)
    private int beforeSold;

    @Column(nullable = false, updatable = false)
    private int afterReserved;

    @Column(nullable = false, updatable = false)
    private int afterSold;

    @Column(nullable = false, updatable = false)
    private int stockTotal;

    @Column(nullable = false, updatable = false, length = 500)
    private String reason;

    @Column(nullable = false, updatable = false, length = 64)
    private String requestedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected StockAdjustmentJpaEntity() {
    }

    public StockAdjustmentJpaEntity(UUID optionId, int beforeReserved, int beforeSold, int afterReserved, int afterSold, int stockTotal,
                                    String reason, String requestedBy, Instant createdAt) {
        this.optionId = optionId;
        this.beforeReserved = beforeReserved;
        this.beforeSold = beforeSold;
        this.afterReserved = afterReserved;
        this.afterSold = afterSold;
        this.stockTotal = stockTotal;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.createdAt = createdAt;
    }
}
