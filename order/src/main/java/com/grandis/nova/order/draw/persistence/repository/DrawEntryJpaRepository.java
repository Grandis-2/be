package com.grandis.nova.order.draw.persistence.repository;

import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;
import com.grandis.nova.order.draw.persistence.entity.DrawEntryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 상태 변경은 전제를 건 UPDATE 다. 바꾼 뒤 영속성 컨텍스트를 비워 같은 트랜잭션의 읽기가 옛 엔티티를 보지 않게 한다. */
public interface DrawEntryJpaRepository extends JpaRepository<DrawEntryJpaEntity, UUID> {

    Optional<DrawEntryJpaEntity> findByCampaignIdAndCustomerId(UUID campaignId, UUID customerId);

    List<DrawEntryJpaEntity> findByCampaignIdAndStatusOrderById(UUID campaignId, DrawEntryStatus status);

    long countByCampaignIdAndStatus(UUID campaignId, DrawEntryStatus status);

    List<DrawEntryJpaEntity> findByCampaignIdAndStatusAndIdIn(UUID campaignId, DrawEntryStatus status, Collection<UUID> ids);

    /** 응모와 그 회차의 반영 시각을 한 문장으로 — 같은 스냅샷이라 반영과 당첨 주문 연결(같은 커밋)이 어긋나 보이지 않는다(READ COMMITTED). */
    @Query(value = """
            SELECT e.id AS id, e.campaign_id AS campaignId, e.status AS status, e.created_at AS createdAt, e.won_order_id AS wonOrderId,
                   c.settled_at AS settledAt
              FROM draw_entries e JOIN draw_campaigns c ON c.id = e.campaign_id
             WHERE e.customer_id = :customerId AND (:wonOnly = FALSE OR e.won_order_id IS NOT NULL)
             ORDER BY e.created_at DESC, e.id DESC
             LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<MyEntryRow> findByCustomerNewestFirst(@Param("customerId") UUID customerId, @Param("wonOnly") boolean wonOnly,
                                               @Param("offset") int offset, @Param("limit") int limit);

    @Query(value = "SELECT COUNT(*) AS total, COUNT(won_order_id) AS won FROM draw_entries WHERE customer_id = :customerId", nativeQuery = true)
    CountsRow countByCustomer(@Param("customerId") UUID customerId);

    @Query(value = "SELECT id FROM draw_entries WHERE campaign_id = :campaignId AND won_order_id IS NOT NULL", nativeQuery = true)
    List<byte[]> findWinnerIds(@Param("campaignId") UUID campaignId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE draw_entries SET won_order_id = :orderId, updated_at = :now
             WHERE id = :id AND campaign_id = :campaignId AND status = 'PAID' AND won_order_id IS NULL""", nativeQuery = true)
    int markWon(@Param("campaignId") UUID campaignId, @Param("id") UUID id, @Param("orderId") UUID orderId, @Param("now") Instant now);

    @Query(value = "SELECT status FROM draw_entries WHERE id = :id", nativeQuery = true)
    Optional<String> findStatus(@Param("id") UUID id);

    @Query(value = "SELECT status FROM draw_entries WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<String> lockStatus(@Param("id") UUID id);

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

    interface MyEntryRow {
        byte[] getId();

        byte[] getCampaignId();

        String getStatus();

        Instant getCreatedAt();

        byte[] getWonOrderId();

        Instant getSettledAt();
    }

    interface CountsRow {
        long getTotal();

        long getWon();
    }
}
