package com.grandis.nova.preorder.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 아웃박스 행 = 메시지 하나. 업무 변경과 같은 트랜잭션에서 INSERT 해서
 * "업무는 커밋됐는데 메시지가 큐에 못 들어간" 창을 없앤다.
 *
 * 여기서는 기록만 한다. 발행(전송 · published_at 채움 · publish_attempts 증가)은 OutboxPublisher 가 한다.
 * updated_at 이 없는 표라 BaseEntity 를 쓰지 않는다.
 */
@Entity
@Table(name = "outbox_events")
@EntityListeners(AuditingEntityListener.class)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 큐 메시지에 그대로 실어 소비자가 중복 수신을 판별한다. */
    @Column(nullable = false, updatable = false, length = 36)
    private String eventId;

    @Column(nullable = false, updatable = false, length = 30)
    private String aggregateType;

    @Column(nullable = false, updatable = false)
    private Long aggregateId;

    @Column(nullable = false, updatable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(nullable = false)
    private int publishAttempts;

    /** 릴레이가 가져간 기한. 그 전에는 다른 릴레이가 이 행을 건너뛴다. NULL 이면 아무도 가져가지 않았다. */
    private Instant leaseUntil;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant publishedAt;

    protected OutboxEvent() {
    }

    OutboxEvent(String eventId, AggregateType aggregateType, Long aggregateId, String eventType, String payload) {
        this.eventId = eventId;
        this.aggregateType = aggregateType.name();
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public int getPublishAttempts() {
        return publishAttempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
