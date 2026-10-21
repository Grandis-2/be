package com.grandis.nova.order.draw.persistence.repository;

import com.grandis.nova.order.draw.persistence.entity.DrawCampaignJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 추첨 요청 · 결과 반영 표식은 엔티티에 매핑하지 않고 전제를 건 UPDATE 로만 쓴다(엔티티는 만든 뒤 바꾸지 않는다). */
public interface DrawCampaignJpaRepository extends JpaRepository<DrawCampaignJpaEntity, UUID> {

    Optional<DrawCampaignJpaEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * 회차 하나. 직접 선언한 쿼리라 트랜잭션 밖에서 부르면 문장 하나다 — 상속한 findById 는 저장소가 읽기 전용 트랜잭션을 열어 준비 · 정리 문장
     * 5개가 더 붙는다(실측 MySQL 8.4 일반 로그).
     */
    @Query("SELECT c FROM DrawCampaignJpaEntity c WHERE c.id = :id")
    Optional<DrawCampaignJpaEntity> lookup(@Param("id") UUID id);

    // 단계 필터 셋은 인덱스 없이 표를 훑는다 — 실측(MySQL 8.4, 회차 5,000 · 응모 중 30 · 예정 100): 목록 100건 1.7 ~ 2.9ms, 건수 0.75ms.
    // 거르지 않은 최신순 목록도 이 크기에서 표를 훑고 2.3ms 다. 회차는 관리자가 만드는 것이라 이 크기를 넘기 어렵다

    /** 예정 — 아직 응모 시작 전. */
    @Query("SELECT c FROM DrawCampaignJpaEntity c WHERE c.opensAt > :now")
    Page<DrawCampaignJpaEntity> findScheduled(@Param("now") Instant now, Pageable pageable);

    /** 응모 중 — 시작 시각 포함, 마감 시각 제외. */
    @Query("SELECT c FROM DrawCampaignJpaEntity c WHERE c.opensAt <= :now AND c.closesAt > :now")
    Page<DrawCampaignJpaEntity> findOpen(@Param("now") Instant now, Pageable pageable);

    /** 마감 — 마감 시각 포함. */
    @Query("SELECT c FROM DrawCampaignJpaEntity c WHERE c.closesAt <= :now")
    Page<DrawCampaignJpaEntity> findClosed(@Param("now") Instant now, Pageable pageable);

    @Query(value = """
            SELECT c.id FROM draw_campaigns c
             WHERE c.closes_at <= :now AND c.draw_requested_at IS NULL AND c.settled_at IS NULL
               AND NOT EXISTS (SELECT 1 FROM draw_entries e WHERE e.campaign_id = c.id AND e.status = 'AUTHORIZING')
             ORDER BY c.closes_at, c.id
             LIMIT :limit""", nativeQuery = true)
    List<byte[]> findDrawable(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "SELECT draw_requested_at AS drawRequestedAt, settled_at AS settledAt FROM draw_campaigns WHERE id = :id", nativeQuery = true)
    Optional<CloseStateRow> findCloseState(@Param("id") UUID id);

    @Query(value = "SELECT draw_requested_at AS drawRequestedAt, settled_at AS settledAt FROM draw_campaigns WHERE id = :id FOR SHARE",
            nativeQuery = true)
    Optional<CloseStateRow> lockCloseState(@Param("id") UUID id);

    @Query(value = """
            SELECT id FROM draw_campaigns
             WHERE closes_at > :closedAfter AND closes_at <= :closedBefore AND settled_at IS NULL ORDER BY closes_at, id LIMIT :limit""",
            nativeQuery = true)
    List<byte[]> findUnsettled(@Param("closedAfter") Instant closedAfter, @Param("closedBefore") Instant closedBefore, @Param("limit") int limit);

    @Query(value = "SELECT id FROM draw_campaigns WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<byte[]> lockRow(@Param("id") UUID id);

    @Query(value = "SELECT id FROM draw_campaigns WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<byte[]> tryLockRow(@Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE draw_campaigns SET draw_requested_at = :now, updated_at = :now
             WHERE id = :id AND draw_requested_at IS NULL AND settled_at IS NULL""", nativeQuery = true)
    int markDrawRequested(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE draw_campaigns SET settled_at = :now, updated_at = :now WHERE id = :id AND settled_at IS NULL", nativeQuery = true)
    int markSettled(@Param("id") UUID id, @Param("now") Instant now);

    /** fk_draw_campaign_option (product_id, option_id) 의 인덱스로 찾는다. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM draw_campaigns WHERE product_id = :productId AND settled_at IS NULL)", nativeQuery = true)
    long existsUnsettledByProductId(@Param("productId") UUID productId);

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

    /**
     * 회차 여러 개의 진행(관리자 목록 한 쪽). 회차마다 따로 세지 않고 한 문장으로 센다 — 집계는 {@link #findProgress} 와 같다.
     * 없는 id 는 줄이 없다. 비용은 그 쪽 회차들의 응모 수에 비례한다(캐시하지 않는다 — 회차별 진행 API 를 쪽 크기만큼 부르는 것과 같은 양).
     */
    @Query(value = """
            SELECT c.id AS campaignId,
                   COALESCE(SUM(e.status = 'PAID'), 0) AS paidEntries,
                   COALESCE(SUM(e.status = 'AUTHORIZING'), 0) AS authorizingEntries,
                   COALESCE(SUM(e.status = 'AWAITING_PAYMENT'), 0) AS awaitingEntries,
                   COALESCE(SUM(e.won_order_id IS NOT NULL), 0) AS winners,
                   c.draw_requested_at AS drawRequestedAt, c.settled_at AS settledAt
              FROM draw_campaigns c LEFT JOIN draw_entries e ON e.campaign_id = c.id
             WHERE c.id IN (:ids)
             GROUP BY c.id, c.draw_requested_at, c.settled_at""", nativeQuery = true)
    List<CampaignProgressRow> findProgressIn(@Param("ids") Collection<UUID> ids);

    interface CloseStateRow {
        Instant getDrawRequestedAt();

        Instant getSettledAt();
    }

    interface CampaignProgressRow extends ProgressRow {
        byte[] getCampaignId();
    }

    interface ProgressRow {
        Long getPaidEntries();

        Long getAuthorizingEntries();

        Long getAwaitingEntries();

        Long getWinners();

        Instant getDrawRequestedAt();

        Instant getSettledAt();
    }
}
