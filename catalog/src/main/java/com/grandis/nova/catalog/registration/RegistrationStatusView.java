package com.grandis.nova.catalog.registration;

import java.time.Instant;

/** 등록 기록의 진행 상태. GET /admin/products/registrations/{key} 와 등록 응답에 실린다. */
public record RegistrationStatusView(
        Long productId,
        String idempotencyKey,
        boolean completed,
        Instant campaignSetAt,
        Instant batchesSetAt,
        Instant stockSetAt,
        Instant completedAt,
        String blockedReason,
        String lastError
) {

    public static RegistrationStatusView from(ProductRegistration registration) {
        return new RegistrationStatusView(registration.getProductId(), registration.getIdempotencyKey(),
                registration.isCompleted(), registration.getCampaignSetAt(), registration.getBatchesSetAt(),
                registration.getStockSetAt(), registration.getCompletedAt(), registration.getBlockedReason(),
                registration.getLastError());
    }
}
