package com.grandis.nova.order.order.persistence.repository;

import com.grandis.nova.order.order.persistence.entity.OrderItemJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderItemJpaRepository extends JpaRepository<OrderItemJpaEntity, Long> {

    List<OrderItemJpaEntity> findByOrderIdOrderById(Long orderId);

    List<OrderItemJpaEntity> findByOrderIdInOrderByOrderIdAscIdAsc(Collection<Long> orderIds);
}
