package com.grandis.nova.preorder.query;

import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;

import java.time.Instant;

/** 관리자 목록 조건. 값이 없으면 null 이고 그 조건은 걸지 않는다. */
record AdminPreorderFilter(
        PreorderStatus status,
        Long customerId,
        Long productId,
        Instant from,
        Instant to,
        SyncJobStatus registerJobStatus
) {
}
