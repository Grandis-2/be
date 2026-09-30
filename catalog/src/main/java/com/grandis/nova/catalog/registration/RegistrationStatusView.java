package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.product.RegistrationSnapshot;

import java.time.Instant;

/** 등록 기록의 진행 상태. GET /admin/products/registrations/{key} · 등록 응답 · 관리자 상세에 실린다. */
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

    /** 상품과 한 문장으로 읽은 등록 칸에서. 관리자 상세가 쓴다. */
    public static RegistrationStatusView from(Long productId, RegistrationSnapshot snapshot) {
        return new RegistrationStatusView(productId, snapshot.idempotencyKey(), snapshot.isCompleted(),
                snapshot.campaignSetAt(), snapshot.batchesSetAt(), snapshot.stockSetAt(), snapshot.completedAt(),
                snapshot.blockedReason(), snapshot.lastError());
    }

    public static RegistrationStatusView from(ProductRegistration registration) {
        return new RegistrationStatusView(registration.getProductId(), registration.getIdempotencyKey(),
                registration.isCompleted(), registration.getCampaignSetAt(), registration.getBatchesSetAt(),
                registration.getStockSetAt(), registration.getCompletedAt(), registration.getBlockedReason(),
                registration.getLastError());
    }
}
