package com.grandis.nova.preorder.deadletter.domain;

import java.time.Instant;
import java.util.UUID;

/** 목록 · 일괄 후보용 요약. 원문(body, 최대 256KB)은 읽지 않는다 — 한 페이지 · 한 묶음마다 큰 문자열을 올리지 않게. */
public interface DeadLetterSummary {

    UUID getId();

    String getMessageId();

    String getEventId();

    String getEventType();

    UUID getPreorderId();

    UUID getCustomerId();

    FailureReason getFailureReason();

    int getReceiveCount();

    DeadLetterStatus getStatus();

    Instant getRedriveStartedAt();

    UUID getRedrivenFromId();

    Instant getSentAt();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
