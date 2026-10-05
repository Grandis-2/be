package com.grandis.nova.waitingroom.domain.admission;

import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;

import java.time.Instant;
import java.util.Objects;

/**
 * 판정 재료 전부. 시각 · 낡음 · 래치까지 주입받는 것은 도메인이 시계도 노드 상태도 보지 않기 때문이다.
 * now 는 Redis 시각으로 보정한 시계, seenFull 은 이 노드가 이 모델의 줄이 찬 것을 방금 봤는가다.
 */
public record AdmissionRequest(
        String productKey,
        ProductState state,
        SnapshotMeta meta,
        Instant now,
        boolean dataStale,
        boolean justEnqueued,
        boolean seenFull
) {

    public AdmissionRequest {
        if (productKey == null || productKey.isBlank()) {
            throw new IllegalArgumentException("productKey 는 필수다");
        }
        Objects.requireNonNull(state, "state 는 필수다");
        Objects.requireNonNull(meta, "meta 는 필수다");
        Objects.requireNonNull(now, "now 는 필수다");
    }

    /** 리미터 창을 가르는 초. */
    public long epochSecond() {
        return now.getEpochSecond();
    }

    /** 낡은 스냅샷은 줄 길이에 얼어 있다. 이 노드가 방금 본 "가득" 이 유일한 재료다. */
    public boolean staleFull() {
        return dataStale && seenFull;
    }
}
