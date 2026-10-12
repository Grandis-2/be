package com.grandis.nova.preorder.syncjob;

import java.time.Instant;

/**
 * 외부 호출 한 번(openapi SyncAttempt). result 가 UNKNOWN 이면 응답을 못 받은 시도다.
 * attemptNumber 는 작업 안의 일련번호, generation · generationAttempt 는 worker 가 센 세대와 그 안의 차례다.
 */
public record SyncAttemptResponse(
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

    public static SyncAttemptResponse from(SyncAttempt attempt) {
        return new SyncAttemptResponse(attempt.attemptNumber(), attempt.generation(),
                attempt.generationAttempt(), attempt.actor(), attempt.result(),
                attempt.httpStatus(), attempt.errorCode(), attempt.errorMessage(),
                attempt.startedAt(), attempt.finishedAt());
    }
}
