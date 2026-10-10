package com.grandis.nova.order.draw.persistence.repository;

import com.grandis.nova.order.draw.persistence.entity.DrawCampaignJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DrawCampaignJpaRepository extends JpaRepository<DrawCampaignJpaEntity, UUID> {

    Optional<DrawCampaignJpaEntity> findByIdempotencyKey(String idempotencyKey);
}
