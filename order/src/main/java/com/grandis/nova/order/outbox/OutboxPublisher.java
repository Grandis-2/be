package com.grandis.nova.order.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * 아웃박스 행을 봉투로 싸서 보내고 결과를 행에 남긴다. 커밋 직후 발행과 릴레이가 함께 쓴다.
 * 전송 실패는 삼킨다 — 행이 미발행으로 남아 릴레이가 다시 보낸다.
 *
 * 봉투의 payload 는 저장된 JSON 을 트리로 읽어 싣는다 — 문자열로 실으면 받는 쪽에서 한 번 더 풀어야 한다.
 *
 * common:outbox 이전 시: preorder outbox.publish.OutboxPublisher 의 복사본이다. 엔티티 · 저장소가 package-private 이라
 * 이 모듈은 publish 하위 패키지 없이 outbox 패키지에 둔다. 공통으로 옮길 때 패키지 구성을 한 벌로 정한다.
 */
@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEvents;
    private final MessageTransport transport;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    OutboxPublisher(OutboxEventRepository outboxEvents, MessageTransport transport, JsonMapper jsonMapper,
                    Clock clock) {
        this.outboxEvents = outboxEvents;
        this.transport = transport;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** 이미 발행된 행(릴레이가 먼저 보냄)은 건너뛴다. */
    void publishById(Long outboxEventId) {
        outboxEvents.findById(outboxEventId)
                .filter(event -> event.getPublishedAt() == null)
                .ifPresent(this::publish);
    }

    /** @return 보내고 발행 완료로 표시했으면 true. 이미 표시된 행이면 false */
    boolean publish(OutboxEvent event) {
        try {
            transport.send(toMessage(event));
        } catch (RuntimeException e) {
            outboxEvents.recordFailure(event.getId());
            log.warn("아웃박스 발행 실패 — 릴레이가 다시 보낸다 outboxEventId={} eventType={}",
                    event.getId(), event.getEventType(), e);
            return false;
        }
        return outboxEvents.markPublished(event.getId(), clock.instant()) == 1;
    }

    private OutboundMessage toMessage(OutboxEvent event) {
        OutboundEventType type = OutboundEventType.valueOf(event.getEventType());
        EventEnvelope envelope = new EventEnvelope(event.getEventId(), event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), event.getCreatedAt(), jsonMapper.readTree(event.getPayload()));
        return new OutboundMessage(type.destination(), event.getEventId(), event.getEventType(),
                jsonMapper.writeValueAsString(envelope));
    }
}
