package com.grandis.nova.order.order.persistence.repository;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.persistence.entity.OrderJpaEntity;
import org.springframework.data.domain.Limit;
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

    Optional<OrderJpaEntity> findByPreorderId(UUID preorderId);

    /**
     * 선언 쿼리라 트랜잭션을 열지 않는다 — JpaRepository 의 기본 메서드는 읽기 전용 트랜잭션을 따로 열어 준비 · 정리 문장이 붙는다.
     * 상태로 시작하는 색인(ix_order_due · ix_order_status_created 중 옵티마이저가 고른 것)의 앞 칸만 읽는다(EXPLAIN: Using index).
     */
    @Query("select o.status as status, count(o) as count from OrderJpaEntity o where o.status in :statuses group by o.status")
    List<StatusCountRow> countByStatusIn(@Param("statuses") Collection<OrderStatus> statuses);

    /**
     * 그 상품이 든 주문의 상태별 수. 상품 줄이 여럿인 주문도 한 번만 세도록 EXISTS 로 건다 — ix_order_item_product 로 그 상품의 주문상품부터
     * 읽고 주문은 기본 키로 읽는다. 비용은 그 상품의 주문 수에 비례한다. 트랜잭션은 {@link #countByStatusIn} 과 같다.
     */
    @Query("""
            select o.status as status, count(o) as count from OrderJpaEntity o
             where exists (select 1 from OrderItemJpaEntity i where i.orderId = o.id and i.productId = :productId)
             group by o.status""")
    List<StatusCountRow> countByStatusOfProduct(@Param("productId") UUID productId);

    /** 그 회원의 상태별 주문 수. 트랜잭션은 {@link #countByStatusIn} 과 같다. */
    @Query("select o.status as status, count(o) as count from OrderJpaEntity o where o.customerId = :customerId group by o.status")
    List<StatusCountRow> countByStatusOfCustomer(@Param("customerId") UUID customerId);

    /**
     * 결제 확인 실패로 취소한 주문(unconfirmed_provider_order_id 가 있다)을 취소 시각(같은 UPDATE 에서 적는 stock_released_at)으로 센다.
     * ix_order_released_unconfirmed 의 since24h 뒤 범위만 읽고 행은 읽지 않는다(EXPLAIN: range · Using index). 트랜잭션은
     * {@link #countByStatusIn} 과 같다.
     */
    @Query("""
            select count(o) as last24h, coalesce(sum(case when o.stockReleasedAt >= :since1h then 1 else 0 end), 0) as last1h
              from OrderJpaEntity o
             where o.stockReleasedAt >= :since24h and o.unconfirmedProviderOrderId is not null""")
    PaymentUnconfirmedRow countPaymentUnconfirmed(@Param("since1h") Instant since1h, @Param("since24h") Instant since24h);

    Optional<OrderJpaEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, UUID idempotencyKey);

    List<OrderJpaEntity> findByCustomerIdAndSourceAndStatusIn(UUID customerId, OrderSource source, Collection<OrderStatus> statuses);

    /**
     * (기한, id) 이어 읽기. 같은 기한은 id 로 가른다. ix_order_due(status, payment_due_at, + PK id) 범위로 읽고 정렬하지 않는다
     * (orders 20,000행 · 기한 지난 1,603행 실측: 첫 쪽 · 중간 쪽 모두 type=range · filesort 없음 · LIMIT 100 에서 100행만 읽음).
     */
    @Query("""
            select o from OrderJpaEntity o
             where o.source in :sources and o.status = :status and o.paymentDueAt <= :now
               and (o.paymentDueAt > :afterDue or (o.paymentDueAt = :afterDue and o.id > :afterId))
             order by o.paymentDueAt asc, o.id asc
            """)
    List<OrderJpaEntity> findExpiredAfter(@Param("sources") Collection<OrderSource> sources, @Param("status") OrderStatus status,
                                          @Param("now") Instant now, @Param("afterDue") Instant afterDue, @Param("afterId") UUID afterId,
                                          Limit limit);

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

    /** MySQL 은 SET 을 왼쪽부터 적용한다 — 결제창 번호를 옮겨 적은 뒤 지운다(순서를 바꾸면 빈 값이 옮겨진다). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderJpaEntity o
               set o.unconfirmedProviderOrderId = o.authorizingProviderOrderId, o.authorizingProviderOrderId = null,
                   o.status = com.grandis.nova.order.order.domain.enums.OrderStatus.CANCELED, o.stockReleasedAt = :now,
                   o.eventSequence = o.eventSequence + 1, o.updatedAt = :now
             where o.id = :id and o.status = com.grandis.nova.order.order.domain.enums.OrderStatus.AUTHORIZING
               and o.source in (com.grandis.nova.order.order.domain.enums.OrderSource.CART,
                                com.grandis.nova.order.order.domain.enums.OrderSource.BUY_NOW)
               and o.stockReleasedAt is null
            """)
    int cancelUnconfirmedReleasingStock(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderJpaEntity o
               set o.autoRefundRequestedAt = :now, o.eventSequence = o.eventSequence + 1, o.updatedAt = :now
             where o.id = :id and o.status = com.grandis.nova.order.order.domain.enums.OrderStatus.CANCELED
               and o.unconfirmedProviderOrderId = :providerOrderId and o.autoRefundRequestedAt is null
            """)
    int markAutoRefundRequested(@Param("id") UUID id, @Param("providerOrderId") String providerOrderId,
                                @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderJpaEntity o
               set o.refundFailedAt = :now, o.eventSequence = o.eventSequence + 1, o.updatedAt = :now
             where o.id = :id and o.refundFailedAt is null
               and (o.status = com.grandis.nova.order.order.domain.enums.OrderStatus.CANCELING
                    or (o.status = com.grandis.nova.order.order.domain.enums.OrderStatus.CANCELED
                        and o.autoRefundRequestedAt is not null))
            """)
    int markRefundFailed(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderJpaEntity o
               set o.paidAt = :paidAt
             where o.id = :id and o.status = com.grandis.nova.order.order.domain.enums.OrderStatus.AWAITING_CONFIRMATION
               and o.paidAt is null
            """)
    int markPaid(@Param("id") UUID id, @Param("paidAt") Instant paidAt);

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderJpaEntity o
               set o.preorderCancelSequence = :cancelSequence
             where o.id = :id and o.status = com.grandis.nova.order.order.domain.enums.OrderStatus.CANCELING
               and o.source = com.grandis.nova.order.order.domain.enums.OrderSource.PREORDER
               and o.preorderCancelSequence is null
            """)
    int markPreorderCancelSequence(@Param("id") UUID id, @Param("cancelSequence") long cancelSequence);

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderJpaEntity o
               set o.preorderCancelReportedAt = :now
             where o.id = :id and o.status = com.grandis.nova.order.order.domain.enums.OrderStatus.CANCELED
               and o.preorderCancelSequence is not null and o.preorderCancelReportedAt is null
            """)
    int markPreorderCancelReported(@Param("id") UUID id, @Param("now") Instant now);

    /** 스칼라 조회라 영속성 컨텍스트의 엔티티가 아니라 DB 값을 읽는다. */
    @Query("select o.preorderCancelSequence from OrderJpaEntity o where o.id = :id")
    Optional<Long> findPreorderCancelSequence(@Param("id") UUID id);

    /** 스칼라 조회라 영속성 컨텍스트의 엔티티가 아니라 DB 값을 읽는다. */
    @Query("""
            select o.preorderCancelSequence from OrderJpaEntity o
             where o.id = :id and o.preorderCancelReportedAt is not null
            """)
    Optional<Long> findReportedPreorderCancelSequence(@Param("id") UUID id);

    /** 스칼라 조회라 영속성 컨텍스트의 엔티티가 아니라 DB 값을 읽는다. 행을 잠근 트랜잭션에서 부른다. */
    @Query("select o.unconfirmedProviderOrderId from OrderJpaEntity o where o.id = :id")
    Optional<String> findUnconfirmedProviderOrderId(@Param("id") UUID id);

    /** 스칼라 조회라 영속성 컨텍스트의 엔티티가 아니라 DB 값을 읽는다. */
    @Query("select o.autoRefundRequestedAt from OrderJpaEntity o where o.id = :id")
    Optional<Instant> findAutoRefundRequestedAt(@Param("id") UUID id);

    /** 스칼라 조회라 영속성 컨텍스트의 엔티티가 아니라 DB 값을 읽는다. 행을 잠근 트랜잭션에서 부른다. */
    @Query("select o.authorizingProviderOrderId from OrderJpaEntity o where o.id = :id")
    Optional<String> findAuthorizingProviderOrderId(@Param("id") UUID id);

    /**
     * 스칼라 조회라 영속성 컨텍스트의 엔티티가 아니라 DB 값을 읽는다. 칸이 비었으면 승인 반영 이력(승인 중 → 결제 확인 대기, 주문당 하나)의
     * 시각을 대신 읽는다 — 마이그레이션이 채운 값과 같은 규칙이다.
     */
    @Query("""
            select coalesce(o.paidAt,
                   (select e.createdAt from OrderEventJpaEntity e
                     where e.orderId = o.id
                       and e.fromStatus = com.grandis.nova.order.order.domain.enums.OrderStatus.AUTHORIZING
                       and e.toStatus = com.grandis.nova.order.order.domain.enums.OrderStatus.AWAITING_CONFIRMATION))
              from OrderJpaEntity o where o.id = :id
            """)
    Optional<Instant> findPaidAt(@Param("id") UUID id);

    @Query(value = "SELECT status FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<OrderStatus> findStatusForUpdate(@Param("id") UUID id);

    @Query("select o.eventSequence from OrderJpaEntity o where o.id = :id")
    long findEventSequence(@Param("id") UUID id);

    interface StatusCountRow {
        OrderStatus getStatus();

        long getCount();
    }

    interface PaymentUnconfirmedRow {
        long getLast1h();

        long getLast24h();
    }
}
