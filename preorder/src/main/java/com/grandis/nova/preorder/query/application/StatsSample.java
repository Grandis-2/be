package com.grandis.nova.preorder.query.application;

import com.grandis.nova.preorder.preorder.PreorderStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 한 시각의 예약 수.
 *
 * @param received    지금까지 접수한 수(모든 상태의 합)
 * @param pendingSync 외부 등록을 기다리는 수
 * @param registered  외부 등록을 마치고 결제를 기다리는 수
 * @param reserved    결제까지 마친 수
 */
public record StatsSample(Instant at, long received, long pendingSync, long registered, long reserved) {

    static StatsSample of(Instant at, Map<PreorderStatus, Long> byStatus) {
        return new StatsSample(at, byStatus.values().stream().mapToLong(Long::longValue).sum(),
                byStatus.getOrDefault(PreorderStatus.PENDING_SYNC, 0L),
                byStatus.getOrDefault(PreorderStatus.REGISTERED, 0L),
                byStatus.getOrDefault(PreorderStatus.RESERVED, 0L));
    }
}
