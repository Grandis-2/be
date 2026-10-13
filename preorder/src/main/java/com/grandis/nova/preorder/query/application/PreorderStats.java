package com.grandis.nova.preorder.query.application;

import com.grandis.nova.preorder.deadletter.DeadLetterWaiting;
import com.grandis.nova.preorder.preorder.PreorderBacklog;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 운영 현황.
 *
 * @param productId 센 범위. null 이면 전체다
 * @param syncJobs  끝나지 않은 작업만(종류 → 상태 → 수)
 * @param countedAt 센 시각. 캐시된 값이면 지금보다 이전이다
 */
public record PreorderStats(
        UUID productId,
        PreorderBacklog preorders,
        Map<SyncJobType, Map<SyncJobStatus, Long>> syncJobs,
        DeadLetterWaiting deadLetters,
        Instant countedAt
) {
}
