package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * DLQ 에서 옮겨 온 메시지 하나. 원문을 그대로 두고, 봉투에서 읽은 칸은 검색용이다.
 * 상태와 처리 기록은 변경 감지로 바꾸지 않는다 — 전이는 {@link DeadLetterEventRepository} 의 조건부 UPDATE 로만 한다.
 */
@Entity
@Table(name = "dead_letter_events")
class DeadLetterEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 80)
    private String sourceQueue;

    @Column(nullable = false, updatable = false, length = 100)
    private String messageId;

    @Column(updatable = false, length = 36)
    private String eventId;

    @Column(updatable = false, length = 50)
    private String eventType;

    @Column(updatable = false, length = 30)
    private String aggregateType;

    @Column(updatable = false)
    private Long aggregateId;

    @Column(updatable = false)
    private Long preorderId;

    @Column(updatable = false)
    private Long customerId;

    @Column(nullable = false, updatable = false, columnDefinition = "mediumtext")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private FailureReason failureReason;

    @Column(nullable = false, updatable = false)
    private int receiveCount;

    @Column(updatable = false)
    private Instant sentAt;

    @Column(updatable = false)
    private Long redrivenFromId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private DeadLetterStatus status;

    @Column(insertable = false, updatable = false, length = 64)
    private String redriveRequestedBy;

    @Column(insertable = false, updatable = false)
    private Instant redriveStartedAt;

    @Column(insertable = false, updatable = false)
    private Instant redrivenAt;

    @Column(insertable = false, updatable = false)
    private Instant outcomeAt;

    @Column(insertable = false, updatable = false, length = 64)
    private String discardedBy;

    @Column(insertable = false, updatable = false)
    private Instant discardedAt;

    @Column(insertable = false, updatable = false, length = 500)
    private String discardNote;

    protected DeadLetterEvent() {
    }

    DeadLetterEvent(IncomingDeadLetter incoming, DeadLetterBody parsed, Long preorderId, Long customerId) {
        this.sourceQueue = incoming.sourceQueue();
        this.messageId = incoming.messageId();
        this.body = incoming.body();
        this.receiveCount = incoming.receiveCount();
        this.sentAt = incoming.sentAt();
        this.redrivenFromId = incoming.redrivenFromId();
        this.eventId = parsed.eventId();
        this.eventType = parsed.eventType();
        this.aggregateType = parsed.aggregateType();
        this.aggregateId = parsed.aggregateId();
        this.failureReason = parsed.failureReason();
        this.preorderId = preorderId;
        this.customerId = customerId;
        this.status = DeadLetterStatus.OPEN;
    }

    /** 되돌리기를 기다리는가 — OPEN 이거나, 보내다 멈춰 staleBefore 전부터 REDRIVING 이다. */
    boolean waitingForRedrive(Instant staleBefore) {
        return status.waitingForRedrive(redriveStartedAt, staleBefore);
    }

    public Long getId() {
        return id;
    }

    public String getSourceQueue() {
        return sourceQueue;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public Long getPreorderId() {
        return preorderId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getBody() {
        return body;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public int getReceiveCount() {
        return receiveCount;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Long getRedrivenFromId() {
        return redrivenFromId;
    }

    public DeadLetterStatus getStatus() {
        return status;
    }

    public String getRedriveRequestedBy() {
        return redriveRequestedBy;
    }

    public Instant getRedriveStartedAt() {
        return redriveStartedAt;
    }

    public Instant getRedrivenAt() {
        return redrivenAt;
    }

    public Instant getOutcomeAt() {
        return outcomeAt;
    }

    public String getDiscardedBy() {
        return discardedBy;
    }

    public Instant getDiscardedAt() {
        return discardedAt;
    }

    public String getDiscardNote() {
        return discardNote;
    }
}
