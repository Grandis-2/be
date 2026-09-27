package com.grandis.nova.catalog.product;

import java.time.Instant;

/**
 * 상품과 그 등록 기록의 완료 시각을 한 SELECT 로 읽은 결과. 등록 기록이 없으면 completedAt 은 null 이다.
 *
 * 둘을 따로 읽으면 READ COMMITTED 가 문장마다 스냅샷을 새로 잡아, 사이에 완료 트랜잭션(visible=1 + completed_at)이
 * 커밋되면 한순간도 없던 조합(visible=true · completed=false)이 나올 수 있다(실측). 한 문장이면 한 스냅샷이다.
 */
public record ProductWithRegistration(Product product, Instant completedAt) {

    public boolean isRegistrationCompleted() {
        return completedAt != null;
    }
}
