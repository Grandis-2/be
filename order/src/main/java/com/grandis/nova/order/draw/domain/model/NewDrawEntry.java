package com.grandis.nova.order.draw.domain.model;

import com.grandis.nova.order.order.vo.ShipTo;

import java.util.Objects;
import java.util.UUID;

/** 넣을 응모. 결제 대기로 들어간다. */
public record NewDrawEntry(UUID campaignId, UUID customerId, ShipTo shipTo) {

    public NewDrawEntry {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(shipTo, "shipTo");
    }
}
