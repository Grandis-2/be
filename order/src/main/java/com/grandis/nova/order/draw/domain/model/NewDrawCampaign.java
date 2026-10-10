package com.grandis.nova.order.draw.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 새 회차(저장 전). 증정품 스냅샷은 만드는 쪽이 catalog 에서 읽어 채운다.
 *
 * @param idempotencyKey 관리자 만들기의 Idempotency-Key — 같은 키는 한 회차다(uq_draw_campaign_idempotency)
 */
public record NewDrawCampaign(String idempotencyKey, UUID productId, UUID optionId, String title, String productTitle, String optionTitle, String imageUrl,
                              BigDecimal entryFee, int winnerCount, Instant opensAt, Instant closesAt) {
}
