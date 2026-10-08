package com.grandis.nova.order.order.persistence.repository;

import com.grandis.nova.order.order.persistence.entity.OrderItemJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 항목은 id 순 = 담은 순이다. order_items 에 시각 칸이 없어 id 로 정렬하는데, 한 주문의 항목은 한 트랜잭션에서 차례로 persist 되고
 * Hibernate 의 v7 생성기가 한 JVM 안에서 단조 증가하므로 담은 순서가 그대로 id 순서가 된다.
 */
public interface OrderItemJpaRepository extends JpaRepository<OrderItemJpaEntity, UUID> {

    List<OrderItemJpaEntity> findByOrderIdOrderById(UUID orderId);

    List<OrderItemJpaEntity> findByOrderIdInOrderByOrderIdAscIdAsc(Collection<UUID> orderIds);
}
