package com.grandis.nova.preorder.outbox;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.UUID;

/**
 * 업무 트랜잭션 안에서 아웃박스에 메시지를 적는다.
 *
 * 스스로 트랜잭션을 열지 않는다(MANDATORY). 업무 변경과 한 트랜잭션이어야 아웃박스의 의미가 있다 —
 * 따로 커밋되면 업무가 롤백돼도 메시지가 남거나, 그 반대가 된다.
 *
 * 적은 뒤 {@link OutboxAppended} 를 던진다. 발행기가 커밋 직후 받아 보낸다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxWriter {

    private final OutboxEventRepository outboxEvents;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher eventPublisher;

    public OutboxWriter(OutboxEventRepository outboxEvents, JsonMapper jsonMapper,
                        ApplicationEventPublisher eventPublisher) {
        this.outboxEvents = outboxEvents;
        this.jsonMapper = jsonMapper;
        this.eventPublisher = eventPublisher;
    }

    /** 메시지를 적고 행을 돌려준다. event_id 는 여기서 새 UUID 로 정한다. */
    public OutboxEvent append(OutboxMessage message) {
        Objects.requireNonNull(message.aggregateId(), "aggregateId");
        OutboxEvent event = new OutboxEvent(UUID.randomUUID().toString(), message.aggregateType(),
                message.aggregateId(), message.eventType().name(), jsonMapper.writeValueAsString(message));
        OutboxEvent saved = outboxEvents.save(event);
        eventPublisher.publishEvent(new OutboxAppended(saved.getId()));
        return saved;
    }
}
