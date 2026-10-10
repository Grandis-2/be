package com.grandis.nova.order.draw.domain.model;

import com.grandis.nova.order.order.vo.ShipTo;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 럭키 드로우 응모(draw_entries 한 행). 회차당 회원 하나다. 결제 금액은 회차의 응모비다.
 *
 * @param authorizingProviderOrderId 승인 중일 때만 — 지금 승인을 기다리는 결제창
 * @param shipTo                     응모 때 받은 배송지. 당첨되면 이 배송지로 증정한다
 * @param wonOrderId                 당첨이면 증정 0원 주문. 결과 반영 전 · 낙첨이면 비어 있다
 */
public record DrawEntry(UUID id, UUID campaignId, UUID customerId, DrawEntryStatus status, String authorizingProviderOrderId,
                        ShipTo shipTo, Instant createdAt, UUID wonOrderId) {

    public DrawEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(shipTo, "shipTo");
        Objects.requireNonNull(createdAt, "createdAt");
        if (wonOrderId != null && status != DrawEntryStatus.PAID) {
            throw new IllegalArgumentException("당첨은 결제 완료 응모뿐이다: status=" + status);
        }
        if ((status == DrawEntryStatus.AUTHORIZING) != (authorizingProviderOrderId != null)) {
            throw new IllegalArgumentException("승인 중일 때만 결제창이 있다: status=" + status);
        }
    }
}
