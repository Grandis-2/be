package com.grandis.nova.order.draw.persistence.entity;

import com.grandis.nova.common.BaseEntity;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * draw_entries 행. 엔티티로는 넣기만 한다 — 상태 칸은 전제를 건 UPDATE(DrawEntryJpaRepository)로만 바꾸므로 모든 칸이 updatable=false 다.
 */
@Entity
@Table(name = "draw_entries")
public class DrawEntryJpaEntity extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private UUID campaignId;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private DrawEntryStatus status;

    @Column(updatable = false, length = 64)
    private String authorizingProviderOrderId;

    @Column(nullable = false, updatable = false, length = 50)
    private String shipToName;

    @Column(nullable = false, updatable = false, length = 20)
    private String shipToPhone;

    @Column(nullable = false, updatable = false, length = 10)
    private String shipToPostalCode;

    @Column(nullable = false, updatable = false, length = 200)
    private String shipToLine1;

    @Column(updatable = false, length = 200)
    private String shipToLine2;

    protected DrawEntryJpaEntity() {
    }

    public DrawEntryJpaEntity(UUID campaignId, UUID customerId, String shipToName, String shipToPhone,
                              String shipToPostalCode, String shipToLine1, String shipToLine2) {
        this.campaignId = campaignId;
        this.customerId = customerId;
        this.status = DrawEntryStatus.AWAITING_PAYMENT;
        this.shipToName = shipToName;
        this.shipToPhone = shipToPhone;
        this.shipToPostalCode = shipToPostalCode;
        this.shipToLine1 = shipToLine1;
        this.shipToLine2 = shipToLine2;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public DrawEntryStatus getStatus() {
        return status;
    }

    public String getAuthorizingProviderOrderId() {
        return authorizingProviderOrderId;
    }

    public String getShipToName() {
        return shipToName;
    }

    public String getShipToPhone() {
        return shipToPhone;
    }

    public String getShipToPostalCode() {
        return shipToPostalCode;
    }

    public String getShipToLine1() {
        return shipToLine1;
    }

    public String getShipToLine2() {
        return shipToLine2;
    }
}
