package com.grandis.nova.catalog.outbox;

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
 * payload 는 웹 설정과 분리된 전용 매퍼로 쓴다 — 저장된 본문이자 다른 서비스와의 계약이라 응답 설정에 따라 바뀌면 안 된다
 * (OptionCombination 의 필터 JSON 과 같은 이유. preorder · order 는 주입받은 매퍼를 쓴다).
 * 적은 뒤 {@link OutboxAppended} 를 던진다. 발행기가 커밋 직후 받아 보낸다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxWriter {

    static final JsonMapper PAYLOAD_JSON = JsonMapper.builder().build();

    private final OutboxEventRepository outboxEvents;
    private final ApplicationEventPublisher eventPublisher;

    public OutboxWriter(OutboxEventRepository outboxEvents, ApplicationEventPublisher eventPublisher) {
        this.outboxEvents = outboxEvents;
        this.eventPublisher = eventPublisher;
    }

    /** 메시지를 적고 행 id 를 돌려준다. event_id 는 여기서 새 UUID 로 정한다. */
    public Long append(OutboxMessage message) {
        Objects.requireNonNull(message.aggregateId(), "aggregateId");
        OutboxEvent event = new OutboxEvent(UUID.randomUUID().toString(), message.aggregateType(), message.aggregateId(),
                message.eventType(), PAYLOAD_JSON.writeValueAsString(message));
        Long id = outboxEvents.save(event).getId();
        eventPublisher.publishEvent(new OutboxAppended(id));
        return id;
    }
}
