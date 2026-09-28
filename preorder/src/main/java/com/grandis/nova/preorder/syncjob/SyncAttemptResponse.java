package com.grandis.nova.preorder.syncjob;


import java.time.Instant;

/**
 * 외부 호출 한 번(openapi SyncAttempt). result 가 없고 끝난 시각도 없으면 프로세스가 죽은 시도,
 * result 가 UNKNOWN 이면 응답을 못 받은 시도다.
 */
public record SyncAttemptResponse(
        int attemptNumber,
        String actor,
        String result,
        Integer httpStatus,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {

    public static SyncAttemptResponse from(SyncAttempt attempt) {
        return new SyncAttemptResponse(attempt.attemptNumber(), attempt.actor(), attempt.result(),
                attempt.httpStatus(), attempt.errorCode(), attempt.errorMessage(),
                attempt.startedAt(), attempt.finishedAt());
    }
}
