package com.grandis.nova.preorder.syncjob;

import java.time.Instant;

/**
 * 외부 호출 한 번의 기록(preorder_sync_attempts). worker 가 쓰고 preorder 는 관리자 화면에서 읽기만 한다.
 *
 * result 가 null 이고 finishedAt 도 null 이면 프로세스가 죽은 시도,
 * result 가 UNKNOWN 이면 호출은 갔는데 응답을 못 받은 시도다.
 */
public record SyncAttempt(
        Long syncJobId,
        int attemptNumber,
        String actor,
        String result,
        Integer httpStatus,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
}
