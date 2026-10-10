package com.grandis.nova.order.draw.persistence.adapter;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.NewDrawCampaign;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.draw.persistence.entity.DrawCampaignJpaEntity;
import com.grandis.nova.order.draw.persistence.repository.DrawCampaignJpaRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaDrawCampaignStore implements DrawCampaignStore {

    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final DrawCampaignJpaRepository campaigns;
    private final EntityManager entityManager;

    JpaDrawCampaignStore(DrawCampaignJpaRepository campaigns, EntityManager entityManager) {
        this.campaigns = campaigns;
        this.entityManager = entityManager;
    }

    @Override
    public DrawCampaign insert(NewDrawCampaign c) {
        DrawCampaignJpaEntity entity = new DrawCampaignJpaEntity(c.productId(), c.optionId(), c.title(), c.productTitle(), c.optionTitle(),
                c.imageUrl(), c.entryFee(), c.winnerCount(), c.opensAt(), c.closesAt());
        entityManager.persist(entity);
        entityManager.flush();
        return toDomain(entity);
    }

    @Override
    public Optional<DrawCampaign> findById(UUID id) {
        return campaigns.findById(id).map(JpaDrawCampaignStore::toDomain);
    }

    @Override
    public OffsetPage<DrawCampaign> findNewestFirst(int page, int size) {
        Page<DrawCampaignJpaEntity> found = campaigns.findAll(PageRequest.of(page, size, NEWEST_FIRST));
        return OffsetPage.of(found.getContent().stream().map(JpaDrawCampaignStore::toDomain).toList(), page, size, found.getTotalElements());
    }

    private static DrawCampaign toDomain(DrawCampaignJpaEntity e) {
        return new DrawCampaign(e.getId(), e.getProductId(), e.getOptionId(), e.getTitle(), e.getProductTitleSnapshot(),
                e.getOptionTitleSnapshot(), e.getImageUrlSnapshot(), e.getEntryFee(), e.getWinnerCount(), e.getOpensAt(), e.getClosesAt(),
                e.getCreatedAt());
    }
}
