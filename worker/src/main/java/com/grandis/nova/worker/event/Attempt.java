package com.grandis.nova.worker.event;

import com.grandis.nova.worker.job.Actor;
import com.grandis.nova.worker.job.AttemptResult;

import java.time.Instant;

/**
 * 차례 하나의 기록. worker 는 저장하지 않고 결과 이벤트에 실어 보내며, preorder 가 결과 이벤트의 id 로 한 번만 남긴다.
 *
 * @param httpStatus 응답을 못 받았으면 null
 * @param errorCode  외부가 준 오류 코드. 없으면 null
 */
public record Attempt(
        int generation,
        int attempt,
        Actor actor,
        AttemptResult result,
        Integer httpStatus,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
}
