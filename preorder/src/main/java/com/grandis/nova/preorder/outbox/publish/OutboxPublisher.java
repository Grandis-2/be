package com.grandis.nova.preorder.outbox.publish;

import com.grandis.nova.preorder.outbox.EventEnvelope;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxEvent;
import com.grandis.nova.preorder.outbox.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;

/**
 * 아웃박스 행을 봉투로 싸서 보내고 결과를 행에 남긴다. 커밋 직후 발행과 릴레이가 함께 쓴다.
 * 전송 실패는 삼킨다 — 행이 미발행으로 남아 릴레이가 다시 보낸다.
 */
@Component
class OutboxPublisher {

    static final String PUBLISH_METRIC = "preorder.outbox.publish";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEvents;
    private final MessageTransport transport;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    OutboxPublisher(OutboxEventRepository outboxEvents, MessageTransport transport, JsonMapper jsonMapper,
                    Clock clock, MeterRegistry meterRegistry) {
        this.outboxEvents = outboxEvents;
        this.transport = transport;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /** 이미 발행된 행(릴레이가 먼저 보냄)은 건너뛴다. */
    void publishById(Long outboxEventId) {
        outboxEvents.findById(outboxEventId)
                .filter(event -> event.getPublishedAt() == null)
                .ifPresent(event -> publish(event, null));
    }

    /**
     * @param lease 릴레이가 건 리스(없으면 null). 실패하면 이 리스만 푼다
     * @return 보내고 발행 완료로 표시했으면 true. 이미 표시된 행이면 false
     */
    boolean publish(OutboxEvent event, Instant lease) {
        try {
            transport.send(toMessage(event));
        } catch (RuntimeException e) {
            count(event, "failure");
            outboxEvents.recordFailure(event.getId(), lease);
            log.warn("아웃박스 발행 실패 — 릴레이가 다시 보낸다 outboxEventId={} eventType={}",
                    event.getId(), event.getEventType(), e);
            return false;
        }
        count(event, "success");
        return outboxEvents.markPublished(event.getId(), clock.instant()) == 1;
    }

    /** 전송 결과를 이벤트 종류별로 센다. 실패가 늘면 전송 구현 · 큐 장애다. */
    private void count(OutboxEvent event, String outcome) {
        meterRegistry.counter(PUBLISH_METRIC, "eventType", event.getEventType(), "outcome", outcome).increment();
    }

    private OutboundMessage toMessage(OutboxEvent event) {
        OutboundEventType type = OutboundEventType.valueOf(event.getEventType());
        EventEnvelope envelope = new EventEnvelope(event.getEventId(), event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), event.getCreatedAt(), jsonMapper.readTree(event.getPayload()));
        return new OutboundMessage(type.destination(), event.getEventId(), event.getEventType(),
                jsonMapper.writeValueAsString(envelope));
    }
}
