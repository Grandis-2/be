package com.grandis.nova.worker.event;

import com.grandis.nova.worker.job.JobType;

import java.util.UUID;

/**
 * @param reason  끝낸 이유. 외부가 거절한 오류 코드이거나 worker 가 정한 값(세대 소진 등)이다
 * @param attempt 마지막 차례. 호출하지 않고 끝냈으면 null
 */
public record ExternalJobFailed(UUID syncJobId, String preorderId, JobType jobType, String reason, Attempt attempt) {
}
