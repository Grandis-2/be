package com.grandis.nova.common.outbox;

import com.grandis.nova.common.message.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;

/**
 * 아웃박스 행을 봉투로 싸서 보내고 결과를 행에 남긴다. 커밋 직후 발행과 릴레이가 함께 쓴다.
 * 전송 실패는 삼킨다 — 행이 미발행으로 남아 릴레이가 다시 보낸다.
 * 봉투의 payload 는 저장된 JSON 을 트리로 읽어 싣는다 — 문자열로 실으면 받는 쪽에서 한 번 더 풀어야 한다.
 *
 * 보낼 때마다 결과를 로그 한 줄로 남긴다. 이벤트를 찾는 값(event_id 등)은 메시지에 끼우지 않고 키-값으로 단다 —
 * 수집 쪽(OTel 에이전트)이 키-값을 속성으로 옮겨, 로그 저장소에서 event_id 로 찾을 수 있게 한다.
 * payload 는 남기지 않는다(배송지 같은 개인정보가 들어 있을 수 있다).
 */
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxStore store;
    private final OutboxDefinition definition;
    private final MessageTransport transport;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final OutboxMetrics metrics;
    private final OutboundAttributes attributes;

    OutboxPublisher(OutboxStore store, OutboxDefinition definition, MessageTransport transport, JsonMapper jsonMapper,
                    Clock clock, OutboxMetrics metrics) {
        this(store, definition, transport, jsonMapper, clock, metrics, OutboundAttributes.NONE);
    }

    OutboxPublisher(OutboxStore store, OutboxDefinition definition, MessageTransport transport, JsonMapper jsonMapper,
                    Clock clock, OutboxMetrics metrics, OutboundAttributes attributes) {
        this.store = store;
        this.definition = definition;
        this.transport = transport;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.metrics = metrics;
        this.attributes = attributes;
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
        OutboundMessage message;
        try {
            message = toMessage(row);
            transport.send(message);
        } catch (RuntimeException e) {
            metrics.published(row.eventType(), false);
            store.recordFailure(row.id(), lease);
            withEventKeys(log.atWarn(), row)
                    .setCause(e)
                    .log("아웃박스 발행 실패 — 릴레이가 다시 보낸다 outboxEventId={} eventType={}", row.id(), row.eventType());
            return false;
        }
        metrics.published(row.eventType(), true);
        // 보낸 기록이다. 커밋 직후 발행과 릴레이가 같은 행을 둘 다 보낼 수 있어, 한 event_id 에 이 줄이 둘 이상일 수 있다(의도)
        withEventKeys(log.atInfo(), row)
                .addKeyValue("destination", message.destination())
                .log("아웃박스 발행");
        return store.markPublished(row.id(), clock.instant()) == 1;
    }

    /** 이벤트를 찾는 값을 키-값으로 단다. 키 이름은 수집 설정(OTel 에이전트의 structured-attributes 목록)과 맞춘다. */
    private static LoggingEventBuilder withEventKeys(LoggingEventBuilder builder, OutboxRow row) {
        return builder
                .addKeyValue("event_id", row.eventId())
                .addKeyValue("aggregate_type", row.aggregateType())
                .addKeyValue("aggregate_id", String.valueOf(row.aggregateId()))
                .addKeyValue("event_type", row.eventType());
    }

    private OutboundMessage toMessage(OutboxRow row) {
        String destination = definition.destinationOf(row.eventType());
        JsonNode payload = jsonMapper.readTree(row.payload());
        EventEnvelope envelope = new EventEnvelope(row.eventId(), row.eventType(), row.aggregateType(),
                row.aggregateId(), row.createdAt(), payload);
        return new OutboundMessage(destination, row.eventId(), row.eventType(), jsonMapper.writeValueAsString(envelope),
                attributes.of(row.eventType(), payload));
    }
}
