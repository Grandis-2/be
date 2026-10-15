package com.grandis.nova.member.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * shop.notifications 한 행 = 한 회원이 받은 알림 하나. 조회용이다 — 넣기는 {@link NotificationWriter} 가 여러 행 INSERT 로,
 * 읽음 표시는 저장소의 UPDATE 문으로 한다. 칸 이름 · 길이는 DDL 그대로이고 ddl-auto=validate 라 어긋나면 기동이 실패한다.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "kind", nullable = false, length = 50)
    private String kind;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 100)
    private String targetId;

    @Column(name = "aggregate_type", length = 30)
    private String aggregateType;

    @Column(name = "aggregate_id")
    private UUID aggregateId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** 처음 읽은 시각. null 이면 안 읽음 */
    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
    }

    UUID getId() {
        return id;
    }

    String getKind() {
        return kind;
    }

    String getTitle() {
        return title;
    }

    String getBody() {
        return body;
    }

    String getTargetType() {
        return targetType;
    }

    String getTargetId() {
        return targetId;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    Instant getReadAt() {
        return readAt;
    }
}
