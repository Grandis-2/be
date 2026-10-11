package com.grandis.nova.order.draw.api;

import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawPhase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 럭키 드로우 회차. 증정품 이름 · 사진은 만든 때의 스냅샷이다.
 *
 * @param phase 지금의 단계(SCHEDULED · OPEN · CLOSED) — 시작 · 마감 시각으로 가른다
 */
public record DrawResponse(
        UUID drawId,
        String title,
        Gift gift,
        BigDecimal entryFee,
        int winnerCount,
        Instant opensAt,
        Instant closesAt,
        DrawPhase phase,
        Instant createdAt
) {

    public static DrawResponse of(DrawCampaign c, Instant now) {
        return new DrawResponse(c.id(), c.title(), new Gift(c.productId(), c.optionId(), c.productTitle(), c.optionTitle(), c.imageUrl()),
                c.entryFee(), c.winnerCount(), c.opensAt(), c.closesAt(), c.phaseAt(now), c.createdAt());
    }

    /** 증정품. variantId 는 옵션 id 다(장바구니 · 주문과 같은 이름). */
    public record Gift(UUID productId, UUID variantId, String productTitle, String optionTitle, String imageUrl) {
    }
}
