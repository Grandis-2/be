package com.grandis.nova.order.outbox;

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
 * 여기서는 기록만 한다. 발행 결과(published_at · publish_attempts)는 발행기가 벌크 UPDATE 로 채울 칸이라
 * updatable 을 막지 않는다. 엔티티는 이 패키지 밖으로 나가지 않는다 — 밖에는 행 id 만 준다.
 * updated_at 이 없는 표라 BaseEntity 를 쓰지 않는다.
 *
 * common:outbox 이전 시: preorder 의 같은 엔티티는 public 이다(발행기가 하위 패키지에서 쓴다). 칼럼 매핑은 같으므로
 * 한 벌로 합치고, 공개 범위는 공통 모듈의 패키지 구성에 맞춘다. 각 모듈은 @EntityScan · @EnableJpaRepositories 에
 * 공통 패키지를 더해야 한다(자동 스캔은 애플리케이션 패키지만 본다).
 */
@Entity
@Table(name = "outbox_events")
@EntityListeners(AuditingEntityListener.class)
class OutboxEvent {

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

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant publishedAt;

    protected OutboxEvent() {
    }

    OutboxEvent(String eventId, AggregateType aggregateType, Long aggregateId, OutboundEventType eventType,
                String payload) {
        this.eventId = eventId;
        this.aggregateType = aggregateType.name();
        this.aggregateId = aggregateId;
        this.eventType = eventType.name();
        this.payload = payload;
    }

    Long getId() {
        return id;
    }
}
