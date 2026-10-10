package com.grandis.nova.order.draw.persistence.repository;

import com.grandis.nova.order.draw.persistence.entity.DrawCampaignJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 추첨 요청 · 결과 반영 표식은 엔티티에 매핑하지 않고 전제를 건 UPDATE 로만 쓴다(엔티티는 만든 뒤 바꾸지 않는다). */
public interface DrawCampaignJpaRepository extends JpaRepository<DrawCampaignJpaEntity, UUID> {

    Optional<DrawCampaignJpaEntity> findByIdempotencyKey(String idempotencyKey);

    @Query(value = """
            SELECT c.id FROM draw_campaigns c
             WHERE c.closes_at <= :now AND c.draw_requested_at IS NULL AND c.settled_at IS NULL
               AND NOT EXISTS (SELECT 1 FROM draw_entries e WHERE e.campaign_id = c.id AND e.status = 'AUTHORIZING')
             ORDER BY c.closes_at, c.id
             LIMIT :limit""", nativeQuery = true)
    List<byte[]> findDrawable(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "SELECT id FROM draw_campaigns WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<byte[]> lockRow(@Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE draw_campaigns SET draw_requested_at = :now, updated_at = :now
             WHERE id = :id AND draw_requested_at IS NULL AND settled_at IS NULL""", nativeQuery = true)
    int markDrawRequested(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE draw_campaigns SET settled_at = :now, updated_at = :now WHERE id = :id AND settled_at IS NULL", nativeQuery = true)
    int markSettled(@Param("id") UUID id, @Param("now") Instant now);

    @Query(value = """
            SELECT COALESCE(SUM(e.status = 'PAID'), 0) AS paidEntries,
                   COALESCE(SUM(e.status = 'AUTHORIZING'), 0) AS authorizingEntries,
                   COALESCE(SUM(e.status = 'AWAITING_PAYMENT'), 0) AS awaitingEntries,
                   COALESCE(SUM(e.won_order_id IS NOT NULL), 0) AS winners,
                   c.draw_requested_at AS drawRequestedAt, c.settled_at AS settledAt
              FROM draw_campaigns c LEFT JOIN draw_entries e ON e.campaign_id = c.id
             WHERE c.id = :id
             GROUP BY c.id, c.draw_requested_at, c.settled_at""", nativeQuery = true)
    Optional<ProgressRow> findProgress(@Param("id") UUID id);

    interface ProgressRow {
        Long getPaidEntries();

        Long getAuthorizingEntries();

        Long getAwaitingEntries();

        Long getWinners();

        Instant getDrawRequestedAt();

        Instant getSettledAt();
    }
}
