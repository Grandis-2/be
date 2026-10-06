package com.grandis.nova.common.outbox;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
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
        Long id = store.insert(UUID.randomUUID().toString(), message.aggregateType().name(), message.aggregateId(),
                eventType, jsonMapper.writeValueAsString(message), clock.instant());
        eventPublisher.publishEvent(new OutboxAppended(id));
        return id;
    }
}
