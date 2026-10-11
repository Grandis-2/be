package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderHistoryEntry;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * 예약 이력. 추가 전용이라 {@link Immutable} 이다. 순서는 시각이 아니라 event_sequence 로 판정한다.
 * 번호는 예약 행의 카운터(preorders.event_sequence)에서 받는다 — {@link PreorderLedger} 가 발급한다.
 */
@Entity
@Table(name = "preorder_events")
@IdClass(PreorderEvent.Key.class)
@Immutable
public class PreorderEvent {

    /** 접수 이력의 번호. 예약 행의 카운터도 이 값으로 시작한다. */
    public static final long FIRST_SEQUENCE = 1;

    @Id
    private UUID preorderId;

    @Id
    private Long eventSequence;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PreorderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PreorderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EventActor actor;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    protected PreorderEvent() {
    }

    public PreorderEvent(UUID preorderId, long eventSequence, PreorderStatus fromStatus, PreorderStatus toStatus,
                         EventActor actor, String reason, Instant createdAt) {
        actor.requireReason(reason);
        this.preorderId = preorderId;
        this.eventSequence = eventSequence;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    /** 다른 모듈에 넘기는 이력 한 줄. */
    public PreorderHistoryEntry toHistoryEntry() {
        return new PreorderHistoryEntry(getPreorderId(), getEventSequence(), getFromStatus(),
                getToStatus(), getActor(), getReason(), getCreatedAt());
    }

    public UUID getPreorderId() {
        return preorderId;
    }

    public Long getEventSequence() {
        return eventSequence;
    }

    /** 접수 이력이면 null. */
    public PreorderStatus getFromStatus() {
        return fromStatus;
    }

    public PreorderStatus getToStatus() {
        return toStatus;
    }

    public EventActor getActor() {
        return actor;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 복합 키 (preorder_id, event_sequence). */
    public record Key(UUID preorderId, Long eventSequence) implements Serializable {
    }
}
