package com.grandis.nova.order.draw.persistence.adapter;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.order.draw.domain.exception.DrawKeyTakenException;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawPhase;
import com.grandis.nova.order.draw.domain.model.DrawCloseState;
import com.grandis.nova.order.draw.domain.model.DrawProgress;
import com.grandis.nova.order.draw.domain.model.NewDrawCampaign;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.draw.persistence.entity.DrawCampaignJpaEntity;
import com.grandis.nova.order.draw.persistence.repository.DrawCampaignJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaDrawCampaignStore implements DrawCampaignStore {

    /** ER_DUP_ENTRY */
    static final int MYSQL_DUPLICATE_KEY = 1062;
    static final String KEY_CONSTRAINT = "uq_draw_campaign_idempotency";

    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final DrawCampaignJpaRepository campaigns;
    private final EntityManager entityManager;

    JpaDrawCampaignStore(DrawCampaignJpaRepository campaigns, EntityManager entityManager) {
        this.campaigns = campaigns;
        this.entityManager = entityManager;
    }

    @Override
    public DrawCampaign insert(NewDrawCampaign c) {
        DrawCampaignJpaEntity entity = new DrawCampaignJpaEntity(c.idempotencyKey(), c.productId(), c.optionId(), c.title(), c.productTitle(),
                c.modelName(), c.optionTitle(), c.imageUrl(), c.entryFee(), c.winnerCount(), c.opensAt(), c.closesAt());
        try {
            entityManager.persist(entity);
            entityManager.flush();
        } catch (PersistenceException e) {
            if (isKeyTaken(e)) {
                throw new DrawKeyTakenException(e);
            }
            throw e;
        }
        return toDomain(entity);
    }

    @Override
    public Optional<DrawCampaign> findById(UUID id) {
        return campaigns.findById(id).map(JpaDrawCampaignStore::toDomain);
    }

    @Override
    public Optional<DrawCampaign> findByIdempotencyKey(String idempotencyKey) {
        return campaigns.findByIdempotencyKey(idempotencyKey).map(JpaDrawCampaignStore::toDomain);
    }

    @Override
    public List<UUID> findDrawable(Instant now, int limit) {
        return campaigns.findDrawable(now, limit).stream().map(UuidBinary::fromBytes).toList();
    }

    @Override
    public void lock(UUID campaignId) {
        campaigns.lockRow(campaignId).orElseThrow(() -> new IllegalArgumentException("회차가 없다: " + campaignId));
    }

    @Override
    public boolean markDrawRequested(UUID campaignId, Instant now) {
        return campaigns.markDrawRequested(campaignId, now) == 1;
    }

    @Override
    public boolean markSettled(UUID campaignId, Instant now) {
        return campaigns.markSettled(campaignId, now) == 1;
    }

    @Override
    public Optional<DrawProgress> findProgress(UUID campaignId) {
        return campaigns.findProgress(campaignId).map(row -> new DrawProgress(row.getPaidEntries(), row.getAuthorizingEntries(),
                row.getAwaitingEntries(), row.getWinners(), row.getDrawRequestedAt(), row.getSettledAt()));
    }

    @Override
    public Optional<DrawCloseState> findCloseState(UUID campaignId) {
        return campaigns.findCloseState(campaignId).map(row -> new DrawCloseState(row.getDrawRequestedAt(), row.getSettledAt()));
    }

    @Override
    public Optional<DrawCloseState> lockCloseState(UUID campaignId) {
        return campaigns.lockCloseState(campaignId).map(row -> new DrawCloseState(row.getDrawRequestedAt(), row.getSettledAt()));
    }

    @Override
    public List<UUID> findUnsettled(Instant closedAfter, Instant closedBefore, int limit) {
        return campaigns.findUnsettled(closedAfter, closedBefore, limit).stream().map(UuidBinary::fromBytes).toList();
    }

    @Override
    public boolean existsUnsettledByProductId(UUID productId) {
        return campaigns.existsUnsettledByProductId(productId) == 1;
    }

    @Override
    public OffsetPage<DrawCampaign> findNewestFirst(int page, int size) {
        return toPage(campaigns.findAll(PageRequest.of(page, size, NEWEST_FIRST)), page, size);
    }

    @Override
    public OffsetPage<DrawCampaign> findNewestFirst(DrawPhase phase, Instant now, int page, int size) {
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        // µs 로 내린다 — MySQL 은 µs 아래 파라미터를 반올림해, 시작 400ns 전이 예정에서 빠졌다. 저장 시각은 µs 라 내림이 phaseAt 과 같다
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        return toPage(switch (phase) {
            case SCHEDULED -> campaigns.findScheduled(at, request);
            case OPEN -> campaigns.findOpen(at, request);
            case CLOSED -> campaigns.findClosed(at, request);
        }, page, size);
    }

    private static OffsetPage<DrawCampaign> toPage(Page<DrawCampaignJpaEntity> found, int page, int size) {
        return OffsetPage.of(found.getContent().stream().map(JpaDrawCampaignStore::toDomain).toList(), page, size, found.getTotalElements());
    }

    /** 같은 Idempotency-Key 가 이미 있는가 — 벤더 코드(1062)와 제약 이름으로 판정한다(장바구니 줄과 같은 방식). 다른 위반이면 그대로 던진다. */
    static boolean isKeyTaken(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return violation.getErrorCode() == MYSQL_DUPLICATE_KEY
                        && name != null && (name.equals(KEY_CONSTRAINT) || name.endsWith("." + KEY_CONSTRAINT));
            }
        }
        return false;
    }

    private static DrawCampaign toDomain(DrawCampaignJpaEntity e) {
        return new DrawCampaign(e.getId(), e.getProductId(), e.getOptionId(), e.getTitle(), e.getProductTitleSnapshot(),
                e.getModelNameSnapshot(), e.getOptionTitleSnapshot(), e.getImageUrlSnapshot(), e.getEntryFee(), e.getWinnerCount(), e.getOpensAt(), e.getClosesAt(),
                e.getCreatedAt());
    }
}
