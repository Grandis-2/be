package com.grandis.nova.order.draw.persistence.repository;

import com.grandis.nova.order.draw.persistence.entity.DrawEntryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 상태 변경은 전제를 건 UPDATE 다. 바꾼 뒤 영속성 컨텍스트를 비워 같은 트랜잭션의 읽기가 옛 엔티티를 보지 않게 한다. */
public interface DrawEntryJpaRepository extends JpaRepository<DrawEntryJpaEntity, UUID> {

    Optional<DrawEntryJpaEntity> findByCampaignIdAndCustomerId(UUID campaignId, UUID customerId);

    @Query(value = "SELECT status FROM draw_entries WHERE id = :id", nativeQuery = true)
    Optional<String> findStatus(@Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE draw_entries SET status = 'AUTHORIZING', authorizing_provider_order_id = :providerOrderId, updated_at = :now
             WHERE id = :id AND status = 'AWAITING_PAYMENT'""", nativeQuery = true)
    int requestPayment(@Param("id") UUID id, @Param("providerOrderId") String providerOrderId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE draw_entries SET status = 'PAID', authorizing_provider_order_id = NULL, updated_at = :now
             WHERE id = :id AND status = 'AUTHORIZING'""", nativeQuery = true)
    int approve(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE draw_entries SET status = 'PAID', updated_at = :now
             WHERE id = :id AND status = 'AWAITING_PAYMENT'""", nativeQuery = true)
    int approveAwaiting(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE draw_entries SET status = 'AWAITING_PAYMENT', authorizing_provider_order_id = NULL, updated_at = :now
             WHERE id = :id AND status = 'AUTHORIZING' AND authorizing_provider_order_id = :providerOrderId""", nativeQuery = true)
    int revert(@Param("id") UUID id, @Param("providerOrderId") String providerOrderId, @Param("now") Instant now);
}
