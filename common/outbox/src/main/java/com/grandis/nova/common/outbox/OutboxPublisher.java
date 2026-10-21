package com.grandis.nova.common.outbox;

import com.grandis.nova.common.message.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 아웃박스 행을 봉투로 싸서 보내고 결과를 행에 남긴다. 커밋 직후 발행과 릴레이가 함께 쓴다.
 * 전송 실패는 삼킨다 — 행이 미발행으로 남아 릴레이가 다시 보낸다.
 * 같은 목적지의 행은 전송 구현이 받는 만큼 묶어 한 번에 보내고, 성공한 행을 한 문장으로 발행 완료 표시한다.
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

    /** 묶음 하나에 넣는 최대 행 수. */
    int batchSize() {
        return Math.max(1, transport.maxBatchSize());
    }

    /**
     * 행을 전송 한 번에 보낼 묶음으로 나눈다 — 목적지별, {@link #batchSize()} 건까지. 목적지를 모르는 종류의 행은 홀로 둔다
     * (보낼 때 실패로 센다). 묶음 안 순서는 들어온 순서다.
     */
    List<List<OutboxRow>> batches(List<OutboxRow> rows) {
        Map<String, List<OutboxRow>> byDestination = new LinkedHashMap<>();
        List<List<OutboxRow>> batches = new ArrayList<>();
        for (OutboxRow row : rows) {
            String destination;
            try {
                destination = definition.destinationOf(row.eventType());
            } catch (RuntimeException e) {
                batches.add(List.of(row));
                continue;
            }
            byDestination.computeIfAbsent(destination, key -> new ArrayList<>()).add(row);
        }
        int size = batchSize();
        byDestination.values().forEach(group -> {
            for (int from = 0; from < group.size(); from += size) {
                batches.add(List.copyOf(group.subList(from, Math.min(from + size, group.size()))));
            }
        });
        return batches;
    }

    /**
     * 행을 묶어 보낸다. 이미 발행 완료로 표시된 행(다른 쪽이 먼저 보냄)은 다시 보내지만 표시를 덮어쓰지 않는다.
     *
     * @param lease 릴레이가 건 리스(없으면 null). 실패한 행은 이 리스만 푼다
     * @return 이번에 발행 완료로 표시한 행 수
     */
    int publishAll(List<OutboxRow> rows, Instant lease) {
        int marked = 0;
        for (List<OutboxRow> batch : batches(rows)) {
            marked += publishBatch(batch, lease);
        }
        return marked;
    }

    /**
     * @param lease 릴레이가 건 리스(없으면 null). 실패하면 이 리스만 푼다
     * @return 보내고 발행 완료로 표시했으면 true. 이미 표시된 행이면 false
     */
    boolean publish(OutboxRow row, Instant lease) {
        return publishAll(List.of(row), lease) == 1;
    }

    /** 같은 목적지 행 묶음 하나. 메시지를 만들지 못한 행 · 보내지 못한 행은 실패로 남기고 나머지는 한 문장으로 표시한다. */
    private int publishBatch(List<OutboxRow> batch, Instant lease) {
        List<OutboxRow> ready = new ArrayList<>();
        List<OutboundMessage> messages = new ArrayList<>();
        for (OutboxRow row : batch) {
            try {
                messages.add(toMessage(row));
                ready.add(row);
            } catch (RuntimeException e) {
                failed(row, lease, e);
            }
        }
        if (ready.isEmpty()) {
            return 0;
        }
        Map<Integer, RuntimeException> failures = send(messages);
        List<Long> sent = new ArrayList<>();
        for (int i = 0; i < ready.size(); i++) {
            OutboxRow row = ready.get(i);
            RuntimeException failure = failures.get(i);
            if (failure != null) {
                failed(row, lease, failure);
                continue;
            }
            metrics.published(row.eventType(), true);
            // 보낸 기록이다. 커밋 직후 발행과 릴레이가 같은 행을 둘 다 보낼 수 있어, 한 event_id 에 이 줄이 둘 이상일 수 있다(의도)
            withEventKeys(log.atInfo(), row)
                    .addKeyValue("destination", messages.get(i).destination())
                    .log("아웃박스 발행");
            sent.add(row.id());
        }
        return sent.isEmpty() ? 0 : store.markPublished(sent, clock.instant());
    }

    /** 한 건이면 send 로 — 묶지 않는 전송 구현과 같은 길이다. 호출 자체가 실패하면 모두 실패다. */
    private Map<Integer, RuntimeException> send(List<OutboundMessage> messages) {
        try {
            if (messages.size() == 1) {
                transport.send(messages.getFirst());
                return Map.of();
            }
            return transport.sendAll(messages);
        } catch (RuntimeException e) {
            Map<Integer, RuntimeException> all = new LinkedHashMap<>();
            for (int i = 0; i < messages.size(); i++) {
                all.put(i, e);
            }
            return all;
        }
    }

    private void failed(OutboxRow row, Instant lease, RuntimeException cause) {
        metrics.published(row.eventType(), false);
        store.recordFailure(row.id(), lease);
        withEventKeys(log.atWarn(), row)
                .setCause(cause)
                .log("아웃박스 발행 실패 — 릴레이가 다시 보낸다 outboxEventId={} eventType={}", row.id(), row.eventType());
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
                attributes.of(row.eventType(), payload), attributes.messageGroupId(row.eventType(), payload));
    }
}
