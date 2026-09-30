package com.grandis.nova.catalog.product;

import java.time.Instant;

/**
 * 상품과 같은 문장으로 읽은 등록 기록의 상태 칸. 기록이 없는 상품(등록 API 이전에 들어온 행)은 이 객체가 없다.
 * 관리자 화면이 "어디까지 됐고 왜 막혔나" 를 보는 데 쓴다.
 */
public record RegistrationSnapshot(
        String idempotencyKey,
        Instant campaignSetAt,
        Instant batchesSetAt,
        Instant stockSetAt,
        Instant completedAt,
        String blockedReason,
        String lastError
) {

    public boolean isCompleted() {
        return completedAt != null;
    }
}
