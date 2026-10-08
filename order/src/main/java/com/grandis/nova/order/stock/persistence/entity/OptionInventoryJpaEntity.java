package com.grandis.nova.order.stock.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * option_inventories 행. 새 행을 넣을 때만 쓴다 — 읽기는 투영(OptionInventoryJpaRepository.InventoryRow), 변경은 조건부 UPDATE 다.
 * 칼럼이 전부 updatable = false 라 이 엔티티를 쥔 트랜잭션의 변경 감지가 조건부 UPDATE 결과를 덮어쓰지 못한다.
 */
@Entity
@Table(name = "option_inventories")
public class OptionInventoryJpaEntity {

    @Id
    private UUID optionId;

    @Column(nullable = false, updatable = false)
    private int stockTotal;

    @Column(nullable = false, updatable = false)
    private int stockReserved;

    @Column(nullable = false, updatable = false)
    private int stockSold;

    @Column(nullable = false, updatable = false)
    private Instant updatedAt;

    protected OptionInventoryJpaEntity() {
    }

    /** 확보 · 판매 0 인 새 행. */
    public OptionInventoryJpaEntity(UUID optionId, int stockTotal, Instant updatedAt) {
        this.optionId = optionId;
        this.stockTotal = stockTotal;
        this.updatedAt = updatedAt;
    }
}
