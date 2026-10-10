package com.grandis.nova.order.draw.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 럭키 드로우 회차. 응모비를 결제한 응모자 중 추첨해 당첨자에게 증정품(일반 상품의 옵션 하나)을 준다.
 * 증정품 이름 · 사진은 만든 때의 catalog 값 스냅샷이다. 회차를 만들 때 당첨 인원만큼 그 옵션의 재고를 확보해 둔다.
 *
 * @param imageUrl 증정품 썸네일. 없으면 null
 */
public record DrawCampaign(
        UUID id,
        UUID productId,
        UUID optionId,
        String title,
        String productTitle,
        String optionTitle,
        String imageUrl,
        BigDecimal entryFee,
        int winnerCount,
        Instant opensAt,
        Instant closesAt,
        Instant createdAt
) {

    /** 응모비 하한(원) — 토스가 너무 작은 금액을 거절할 수 있다(2026-10-10 결정, ck_draw_campaign_entry_fee). */
    public static final BigDecimal MIN_ENTRY_FEE = new BigDecimal("100");

    public DrawCampaign {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(productTitle, "productTitle");
        Objects.requireNonNull(optionTitle, "optionTitle");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(optionId, "optionId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(entryFee, "entryFee");
        Objects.requireNonNull(opensAt, "opensAt");
        Objects.requireNonNull(closesAt, "closesAt");
        // ck_draw_campaign_entry_fee · ck_draw_campaign_winner_count · ck_draw_campaign_period
        if (entryFee.compareTo(MIN_ENTRY_FEE) < 0 || winnerCount < 1 || !opensAt.isBefore(closesAt)) {
            throw new IllegalArgumentException("응모비는 " + MIN_ENTRY_FEE + "원 이상, 당첨 인원은 1 이상, 응모 시작은 마감 전이다");
        }
    }

    /** now 의 단계. 시작 시각부터 응모 중, 마감 시각부터 마감이다(마감 == now 는 마감). */
    public DrawPhase phaseAt(Instant now) {
        if (now.isBefore(opensAt)) {
            return DrawPhase.SCHEDULED;
        }
        return now.isBefore(closesAt) ? DrawPhase.OPEN : DrawPhase.CLOSED;
    }
}
