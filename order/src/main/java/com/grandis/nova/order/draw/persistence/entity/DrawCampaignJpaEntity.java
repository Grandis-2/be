package com.grandis.nova.order.draw.persistence.entity;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** draw_campaigns 행. 만든 뒤 바꾸지 않는다(모든 칸 updatable=false). */
@Entity
@Table(name = "draw_campaigns")
public class DrawCampaignJpaEntity extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private UUID optionId;

    @Column(nullable = false, updatable = false, length = 100)
    private String title;

    @Column(nullable = false, updatable = false, length = 100)
    private String productTitleSnapshot;

    @Column(nullable = false, updatable = false, length = 120)
    private String optionTitleSnapshot;

    @Column(updatable = false, length = 1000)
    private String imageUrlSnapshot;

    @Column(nullable = false, updatable = false, precision = 12, scale = 0)
    private BigDecimal entryFee;

    @Column(nullable = false, updatable = false)
    private int winnerCount;

    @Column(nullable = false, updatable = false)
    private Instant opensAt;

    @Column(nullable = false, updatable = false)
    private Instant closesAt;

    protected DrawCampaignJpaEntity() {
    }

    public DrawCampaignJpaEntity(String idempotencyKey, UUID productId, UUID optionId, String title, String productTitleSnapshot, String optionTitleSnapshot,
                                 String imageUrlSnapshot, BigDecimal entryFee, int winnerCount, Instant opensAt, Instant closesAt) {
        this.idempotencyKey = idempotencyKey;
        this.productId = productId;
        this.optionId = optionId;
        this.title = title;
        this.productTitleSnapshot = productTitleSnapshot;
        this.optionTitleSnapshot = optionTitleSnapshot;
        this.imageUrlSnapshot = imageUrlSnapshot;
        this.entryFee = entryFee;
        this.winnerCount = winnerCount;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getOptionId() {
        return optionId;
    }

    public String getTitle() {
        return title;
    }

    public String getProductTitleSnapshot() {
        return productTitleSnapshot;
    }

    public String getOptionTitleSnapshot() {
        return optionTitleSnapshot;
    }

    public String getImageUrlSnapshot() {
        return imageUrlSnapshot;
    }

    public BigDecimal getEntryFee() {
        return entryFee;
    }

    public int getWinnerCount() {
        return winnerCount;
    }

    public Instant getOpensAt() {
        return opensAt;
    }

    public Instant getClosesAt() {
        return closesAt;
    }
}
