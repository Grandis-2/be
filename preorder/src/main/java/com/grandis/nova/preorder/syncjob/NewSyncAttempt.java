package com.grandis.nova.preorder.syncjob;

import java.time.Instant;

/**
 * worker 가 결과 이벤트에 실어 보낸 외부 호출 한 번. 칸 이름은 이벤트의 attempt 와 같다.
 *
 * @param run        작업의 몇 번째 실행에서 나온 것인가(작업 메시지에 실어 보낸 값)
 * @param generation 몇 번째 세대인가
 * @param attempt    그 세대의 몇 번째 차례인가
 * @param httpStatus 응답을 못 받았으면 null
 */
public record NewSyncAttempt(
        int run,
        int generation,
        int attempt,
        String actor,
        String result,
        Integer httpStatus,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
}
