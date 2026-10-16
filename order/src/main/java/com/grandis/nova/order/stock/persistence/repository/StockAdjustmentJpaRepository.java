package com.grandis.nova.order.stock.persistence.repository;

import com.grandis.nova.order.stock.persistence.entity.StockAdjustmentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StockAdjustmentJpaRepository extends JpaRepository<StockAdjustmentJpaEntity, UUID> {
}
