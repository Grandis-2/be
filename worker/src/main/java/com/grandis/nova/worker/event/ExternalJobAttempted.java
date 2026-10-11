package com.grandis.nova.worker.event;

import com.grandis.nova.worker.job.JobType;

import java.util.UUID;

public record ExternalJobAttempted(UUID syncJobId, String preorderId, JobType jobType, Attempt attempt) {
}
