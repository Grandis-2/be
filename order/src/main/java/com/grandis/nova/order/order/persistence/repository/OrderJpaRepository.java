package com.grandis.nova.order.order.persistence.repository;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.persistence.entity.OrderJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않는다. 앞에서는 flush 해서 쌓인 변경을 먼저 반영한다.
 * 뒤에서는 이미 읽어 둔 주문 엔티티가 옛 상태를 들고 있는데, 컨텍스트 전체를 비우지(clearAutomatically) 않고
 * 어댑터가 그 주문만 떼어낸다(JpaOrderStore.changeStatus) — 전체를 비우면 같은 트랜잭션의 다른 엔티티(결제 · 재고)까지
 * 떼어져 그 뒤의 변경이 조용히 유실된다.
 */
public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, Long>,
        JpaSpecificationExecutor<OrderJpaEntity> {

    Optional<OrderJpaEntity> findByOrderToken(String orderToken);

    Optional<OrderJpaEntity> findByPreorderId(Long preorderId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderJpaEntity o
               set o.status = :to, o.eventSequence = o.eventSequence + 1, o.updatedAt = :now
             where o.id = :id and o.status = :from
            """)
    int changeStatus(@Param("id") Long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to,
                     @Param("now") Instant now);

    @Query(value = "SELECT status FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<OrderStatus> findStatusForUpdate(@Param("id") Long id);

    @Query("select o.eventSequence from OrderJpaEntity o where o.id = :id")
    long findEventSequence(@Param("id") Long id);
}
