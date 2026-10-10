package com.grandis.nova.order.draw.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 새 회차(저장 전). 증정품 스냅샷은 만드는 쪽이 catalog 에서 읽어 채운다. */
public record NewDrawCampaign(UUID productId, UUID optionId, String title, String productTitle, String optionTitle, String imageUrl,
                              BigDecimal entryFee, int winnerCount, Instant opensAt, Instant closesAt) {
}
