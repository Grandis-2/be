package com.grandis.nova.order.outbox;

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
 * 스스로 트랜잭션을 열지 않는다(MANDATORY). 업무 변경(주문 전이 · 이력)과 한 트랜잭션이어야 아웃박스의 의미가 있다 —
 * 따로 커밋되면 업무가 롤백돼도 메시지가 남거나, 그 반대가 된다. 원장({@code OrderLedger})과 함께 부를 때도
 * 순서는 상관없다: 원장은 바꾼 주문 엔티티 하나만 영속성 컨텍스트에서 떼어낸다.
 *
 * 적은 뒤 {@link OutboxAppended} 를 던진다. 발행기가 커밋 직후 받아 보낸다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxWriter {

    private final OutboxEventRepository outboxEvents;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher eventPublisher;

    OutboxWriter(OutboxEventRepository outboxEvents, JsonMapper jsonMapper, ApplicationEventPublisher eventPublisher) {
        this.outboxEvents = outboxEvents;
        this.jsonMapper = jsonMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 메시지를 적고 행 id 를 돌려준다. event_id 는 여기서 새 UUID 로 정한다.
     * 엔티티는 돌려주지 않는다 — 발행 칸을 가진 JPA 엔티티가 업무 코드로 나가지 않게 한다.
     *
     * common:outbox 이전 시: preorder 의 append 는 OutboxEvent 를 돌려준다. 공통 쪽에서 한 모양으로 정한다 — 이 모듈의
     * 호출자(F6)는 반환값을 쓰지 않는다.
     */
    public Long append(OutboxMessage message) {
        Objects.requireNonNull(message.aggregateId(), "aggregateId");
        OutboxEvent event = new OutboxEvent(UUID.randomUUID().toString(), message.aggregateType(),
                message.aggregateId(), message.eventType(), jsonMapper.writeValueAsString(message));
        Long id = outboxEvents.save(event).getId();
        eventPublisher.publishEvent(new OutboxAppended(id));
        return id;
    }
}
