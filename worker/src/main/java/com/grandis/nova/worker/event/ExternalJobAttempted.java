package com.grandis.nova.worker.event;

import com.grandis.nova.worker.job.JobType;

import java.util.Objects;
import java.util.UUID;

/** 실패한 차례를 알린다. attempt 는 반드시 싣는다 — preorder 가 이 기록의 이벤트 id 로 이미 받은 이벤트를 가린다. */
public record ExternalJobAttempted(UUID syncJobId, String preorderId, JobType jobType, Attempt attempt)
        implements JobResult {

    public ExternalJobAttempted {
        Objects.requireNonNull(attempt, "attempt");
    }
}
