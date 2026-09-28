package com.grandis.nova.catalog.product;

import java.time.Instant;
import java.util.Optional;

/**
 * 상품과 그 등록 기록을 한 SELECT 로 읽은 결과. 등록 기록이 없으면 등록 칸은 전부 null 이다.
 *
 * 둘을 따로 읽으면 READ COMMITTED 가 문장마다 스냅샷을 새로 잡아, 사이에 완료 트랜잭션(visible=1 + completed_at)이
 * 커밋되면 한순간도 없던 조합(visible=true · completed=false)이 나올 수 있다(실측). 한 문장이면 한 스냅샷이다.
 * 등록 칸을 평평하게 두는 것은 JPQL 의 생성자 식이 LEFT JOIN 의 빈 쪽을 null 스칼라로 넘기기 때문이다 — 중첩 객체면 빈 기록이
 * "전부 null 인 객체" 로 나온다.
 */
public record ProductWithRegistration(
        Product product,
        String idempotencyKey,
        Instant campaignSetAt,
        Instant batchesSetAt,
        Instant stockSetAt,
        Instant completedAt,
        String blockedReason,
        String lastError
) {

    public boolean isRegistrationCompleted() {
        return completedAt != null;
    }

    /** 등록 기록이 있으면 그 상태. idempotency_key 가 NOT NULL 이라 기록의 유무를 이 칸으로 가른다. */
    public Optional<RegistrationSnapshot> registration() {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return Optional.of(new RegistrationSnapshot(idempotencyKey, campaignSetAt, batchesSetAt, stockSetAt, completedAt,
                blockedReason, lastError));
    }
}
