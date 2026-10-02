package com.grandis.nova.common.outbox;

import com.grandis.nova.common.message.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;

/**
 * 아웃박스 행을 봉투로 싸서 보내고 결과를 행에 남긴다. 커밋 직후 발행과 릴레이가 함께 쓴다.
 * 전송 실패는 삼킨다 — 행이 미발행으로 남아 릴레이가 다시 보낸다.
 * 봉투의 payload 는 저장된 JSON 을 트리로 읽어 싣는다 — 문자열로 실으면 받는 쪽에서 한 번 더 풀어야 한다.
 */
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxStore store;
    private final OutboxDefinition definition;
    private final MessageTransport transport;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final OutboxMetrics metrics;

    OutboxPublisher(OutboxStore store, OutboxDefinition definition, MessageTransport transport, JsonMapper jsonMapper,
                    Clock clock, OutboxMetrics metrics) {
        this.store = store;
        this.definition = definition;
        this.transport = transport;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** 이미 발행된 행(릴레이가 먼저 보냄)은 건너뛴다. */
    void publishById(Long id) {
        store.find(id)
                .filter(row -> row.publishedAt() == null)
                .ifPresent(row -> publish(row, null));
    }

    /**
     * @param lease 릴레이가 건 리스(없으면 null). 실패하면 이 리스만 푼다
     * @return 보내고 발행 완료로 표시했으면 true. 이미 표시된 행이면 false
     */
    boolean publish(OutboxRow row, Instant lease) {
        try {
            transport.send(toMessage(row));
        } catch (RuntimeException e) {
            metrics.published(row.eventType(), false);
            store.recordFailure(row.id(), lease);
            log.warn("아웃박스 발행 실패 — 릴레이가 다시 보낸다 outboxEventId={} eventType={}", row.id(), row.eventType(), e);
            return false;
        }
        metrics.published(row.eventType(), true);
        return store.markPublished(row.id(), clock.instant()) == 1;
    }

    private OutboundMessage toMessage(OutboxRow row) {
        String destination = definition.destinationOf(row.eventType());
        EventEnvelope envelope = new EventEnvelope(row.eventId(), row.eventType(), row.aggregateType(),
                row.aggregateId(), row.createdAt(), jsonMapper.readTree(row.payload()));
        return new OutboundMessage(destination, row.eventId(), row.eventType(), jsonMapper.writeValueAsString(envelope));
    }
}
