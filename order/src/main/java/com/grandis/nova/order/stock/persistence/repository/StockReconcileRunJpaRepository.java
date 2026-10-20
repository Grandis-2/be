package com.grandis.nova.order.stock.persistence.repository;

import com.grandis.nova.order.stock.persistence.entity.StockReconcileRunJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StockReconcileRunJpaRepository extends JpaRepository<StockReconcileRunJpaEntity, UUID> {
}
