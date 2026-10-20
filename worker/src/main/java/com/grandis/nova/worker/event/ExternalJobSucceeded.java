package com.grandis.nova.worker.event;

import com.grandis.nova.worker.job.JobType;

import java.util.UUID;

/** @param externalNumber 외부가 준 예약 번호. CANCEL 은 null 일 수 있다 */
public record ExternalJobSucceeded(UUID syncJobId, String preorderId, JobType jobType, String externalNumber,
                                   Attempt attempt) implements JobResult {
}
