package com.grandis.nova.order.cart.persistence.entity;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * cart_items 행. 새 행을 넣을 때만 쓴다 — 읽기는 투영(CartItemJpaRepository.CartRow), 수량 변경은 조건부 UPDATE 다.
 * 칼럼이 전부 updatable = false 라 이 엔티티를 쥔 트랜잭션의 변경 감지가 UPDATE 결과를 덮어쓰지 못한다(OptionInventoryJpaEntity 와 같은 이유).
 */
@Entity
@Table(name = "cart_items")
public class CartItemJpaEntity extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, updatable = false)
    private UUID optionId;

    @Column(name = "warranty_selected", nullable = false, updatable = false)
    private boolean warrantySelected;

    @Column(nullable = false, updatable = false)
    private int quantity;

    protected CartItemJpaEntity() {
    }

    public CartItemJpaEntity(UUID customerId, UUID optionId, boolean warrantySelected, int quantity) {
        this.customerId = customerId;
        this.optionId = optionId;
        this.warrantySelected = warrantySelected;
        this.quantity = quantity;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getOptionId() {
        return optionId;
    }

    public boolean isWarrantySelected() {
        return warrantySelected;
    }

    public int getQuantity() {
        return quantity;
    }
}
