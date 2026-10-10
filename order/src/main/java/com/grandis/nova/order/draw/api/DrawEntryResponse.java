package com.grandis.nova.order.draw.api;

import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 내 응모. 본인에게만 보인다.
 *
 * @param status AWAITING_PAYMENT(응모비 결제 전 — 응모 기간 안에 결제해야 추첨 대상) · AUTHORIZING(결제 확인 중) · PAID(추첨 대상)
 */
public record DrawEntryResponse(UUID entryId, UUID drawId, DrawEntryStatus status, ShipTo shipTo, Instant createdAt) {

    static DrawEntryResponse of(DrawEntry entry) {
        var to = entry.shipTo();
        return new DrawEntryResponse(entry.id(), entry.campaignId(), entry.status(),
                new ShipTo(to.name(), to.phone(), to.postalCode(), to.line1(), to.line2()), entry.createdAt());
    }

    /** 응모 때 받은 배송지(본인에게만). 개인정보라 toString 에 값을 싣지 않는다. */
    public record ShipTo(String name, String phone, String postalCode, String line1, String line2) {

        @Override
        public String toString() {
            return "ShipTo[***]";
        }
    }
}
