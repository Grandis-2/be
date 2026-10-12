package com.grandis.nova.worker.event;

import com.grandis.nova.worker.job.JobType;

import java.util.Objects;
import java.util.UUID;

/**
 * @param reason  끝낸 이유. 외부가 거절한 오류 코드이거나 worker 가 정한 값(세대 소진 등)이다
 * @param attempt 마지막 차례. 반드시 싣는다 — preorder 가 이 기록의 이벤트 id 로 이미 받은 이벤트를 가린다
 */
public record ExternalJobFailed(UUID syncJobId, String preorderId, JobType jobType, String reason, Attempt attempt) {

    public ExternalJobFailed {
        Objects.requireNonNull(attempt, "attempt");
    }
}
