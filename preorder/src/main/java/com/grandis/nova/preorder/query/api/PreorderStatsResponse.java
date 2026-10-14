package com.grandis.nova.preorder.query.api;

import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.query.application.PreorderStats;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 운영 현황. 숫자를 눌러 볼 목록은 예약 · 동기화 작업 · DLQ 목록에 같은 조건을 걸어 본다.
 *
 * @param productId           센 범위. 전체면 null
 * @param preorders           원장 상태별 예약 수
 * @param syncJobs            끝나지 않은 동기화 작업 수(종류 → 상태). 성공 · 무효화한 작업은 세지 않는다
 * @param paymentOverdue      결제 기한이 지났는데 아직 만료 처리 전인 예약 수
 * @param oldestPendingSyncAt 외부 등록을 기다리는 예약 중 가장 오래된 접수 시각. 없으면 null
 * @param countedAt           센 시각. 이미 받은 것보다 이전이면 늦게 온 응답이다
 */
public record PreorderStatsResponse(
        UUID productId,
        Map<PreorderStatus, Long> preorders,
        Map<SyncJobType, Map<SyncJobStatus, Long>> syncJobs,
        DeadLetters deadLetters,
        long paymentOverdue,
        Instant oldestPendingSyncAt,
        Instant countedAt
) {

    /**
     * @param waiting         되돌리기를 기다리는 DLQ 메시지 수
     * @param oldestWaitingAt 그중 가장 오래된 것이 쌓인 시각. 없으면 null
     */
    public record DeadLetters(long waiting, Instant oldestWaitingAt) {
    }

    public static PreorderStatsResponse from(PreorderStats stats) {
        return new PreorderStatsResponse(stats.productId(), stats.preorders().byStatus(), stats.syncJobs(),
                new DeadLetters(stats.deadLetters().count(), stats.deadLetters().oldestAt()),
                stats.preorders().paymentOverdue(), stats.preorders().oldestPendingSyncAt(), stats.countedAt());
    }
}
