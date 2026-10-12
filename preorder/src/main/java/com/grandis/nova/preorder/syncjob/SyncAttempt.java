package com.grandis.nova.preorder.syncjob;

import java.time.Instant;
import java.util.UUID;

/**
 * 외부 호출 한 번의 기록(preorder_sync_attempts). worker 가 보낸 결과 이벤트를 받아 남긴 것이다.
 * result 가 UNKNOWN 이면 호출은 갔는데 응답을 못 받은 시도다.
 *
 * @param attemptNumber     작업 안의 일련번호(받은 순서)
 * @param generation        worker 가 센 세대
 * @param generationAttempt 그 세대의 몇 번째 차례인가
 */
public record SyncAttempt(
        UUID syncJobId,
        int attemptNumber,
        int generation,
        int generationAttempt,
        String actor,
        String result,
        Integer httpStatus,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
}
