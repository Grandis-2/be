package com.grandis.nova.order.order.persistence.repository;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.persistence.entity.OrderJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않는다. 앞에서는 flush 해서 쌓인 변경을 먼저 반영한다.
 * 뒤에서는 이미 읽어 둔 주문 엔티티가 옛 상태를 들고 있는데, 컨텍스트 전체를 비우지(clearAutomatically) 않고
 * 어댑터가 그 주문만 떼어낸다(JpaOrderStore.changeStatus) — 전체를 비우면 같은 트랜잭션의 다른 엔티티(결제 · 재고)까지
 * 떼어져 그 뒤의 변경이 조용히 유실된다.
 */
public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, UUID>,
        JpaSpecificationExecutor<OrderJpaEntity> {

    Optional<OrderJpaEntity> findByOrderToken(String orderToken);

    Optional<OrderJpaEntity> findByPreorderId(UUID preorderId);

    List<OrderJpaEntity> findByCustomerIdAndSourceAndStatusInAndPaymentDueAtAfter(UUID customerId, OrderSource source,
                                                                                Collection<OrderStatus> statuses, Instant after);

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderJpaEntity o
               set o.status = :to, o.authorizingProviderOrderId = :attempt, o.eventSequence = o.eventSequence + 1,
                   o.updatedAt = :now
             where o.id = :id and o.status = :from
            """)
    int changeStatus(@Param("id") UUID id, @Param("from") OrderStatus from, @Param("to") OrderStatus to,
                     @Param("attempt") String authorizingProviderOrderId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderJpaEntity o
               set o.status = com.grandis.nova.order.order.domain.enums.OrderStatus.CANCELED, o.stockReleasedAt = :now,
                   o.eventSequence = o.eventSequence + 1, o.updatedAt = :now
             where o.id = :id and o.status = com.grandis.nova.order.order.domain.enums.OrderStatus.AWAITING_PAYMENT
               and o.source <> com.grandis.nova.order.order.domain.enums.OrderSource.PREORDER and o.stockReleasedAt is null
            """)
    int cancelReleasingStock(@Param("id") UUID id, @Param("now") Instant now);

    /** 스칼라 조회라 영속성 컨텍스트의 엔티티가 아니라 DB 값을 읽는다. 행을 잠근 트랜잭션에서 부른다. */
    @Query("select o.authorizingProviderOrderId from OrderJpaEntity o where o.id = :id")
    Optional<String> findAuthorizingProviderOrderId(@Param("id") UUID id);

    @Query(value = "SELECT status FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<OrderStatus> findStatusForUpdate(@Param("id") UUID id);

    @Query("select o.eventSequence from OrderJpaEntity o where o.id = :id")
    long findEventSequence(@Param("id") UUID id);
}
