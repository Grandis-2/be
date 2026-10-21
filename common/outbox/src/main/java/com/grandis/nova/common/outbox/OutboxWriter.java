package com.grandis.nova.common.outbox;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * 업무 트랜잭션 안에서 아웃박스에 메시지를 적는다.
 *
 * 스스로 트랜잭션을 열지 않는다(MANDATORY). 업무 변경과 한 트랜잭션이어야 아웃박스의 의미가 있다 —
 * 따로 커밋되면 업무가 롤백돼도 메시지가 남거나, 그 반대가 된다.
 * 표에 등록되지 않은 종류는 적지 않는다 — 릴레이가 목적지를 찾지 못해 영영 보내지 못한다.
 *
 * 적은 뒤 {@link OutboxAppended} 를 던진다. 발행기가 커밋 직후 받아 보낸다.
 */
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxWriter {

    private final OutboxStore store;
    private final OutboxDefinition definition;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    OutboxWriter(OutboxStore store, OutboxDefinition definition, JsonMapper jsonMapper,
                 ApplicationEventPublisher eventPublisher, Clock clock) {
        this.store = store;
        this.definition = definition;
        this.jsonMapper = jsonMapper;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /** 메시지를 적고 행 id 를 돌려준다. event_id 는 여기서 새 UUID 로 정한다. */
    public Long append(OutboxMessage message) {
        Objects.requireNonNull(message.aggregateId(), "aggregateId");
        String eventType = message.eventType().name();
        definition.destinationOf(eventType);
        String eventId = UUID.randomUUID().toString();
        String aggregateType = message.aggregateType().name();
        String payload = jsonMapper.writeValueAsString(message);
        // 칸 해상도(마이크로초)로 내린다 — 다시 읽은 행과 같은 값이어야 봉투의 occurredAt 이 릴레이와 같다
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Long id = store.insert(eventId, aggregateType, message.aggregateId(), eventType, payload, createdAt);
        eventPublisher.publishEvent(new OutboxAppended(new OutboxRow(id, eventId, aggregateType, message.aggregateId(),
                eventType, payload, 0, createdAt, null)));
        return id;
    }
}
