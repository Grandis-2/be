package com.grandis.nova.preorder.event;

import com.grandis.nova.preorder.syncjob.NewSyncAttempt;

import java.util.UUID;

/** worker 가 외부 호출에 실패했고 다시 시도한다. 차례 기록(attempt)을 남기려고 받는다 — 없으면 받지 않는다. */
public record ExternalJobAttempted(UUID syncJobId, String preorderId, String jobType, NewSyncAttempt attempt) {
}
