package com.grandis.nova.order.stock.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** stock_reconcile_runs 행 — 끝난 대조 한 번. 넣기만 한다. 읽기는 SQL 로 한다(JpaStockReconcileRuns). */
@Entity
@Table(name = "stock_reconcile_runs")
public class StockReconcileRunJpaEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    @Column(nullable = false, updatable = false)
    private Instant finishedAt;

    @Column(nullable = false, updatable = false)
    private int driftOptions;

    /** JSON 문자열 그대로 넣는다 — 모양은 어댑터가 정한다. */
    @Column(nullable = false, updatable = false, columnDefinition = "json")
    private String detail;

    protected StockReconcileRunJpaEntity() {
    }

    public StockReconcileRunJpaEntity(Instant startedAt, Instant finishedAt, int driftOptions, String detail) {
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.driftOptions = driftOptions;
        this.detail = detail;
    }
}
