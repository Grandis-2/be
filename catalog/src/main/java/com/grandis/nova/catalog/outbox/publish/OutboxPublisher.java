package com.grandis.nova.catalog.outbox.publish;

import com.grandis.nova.catalog.outbox.EventEnvelope;
import com.grandis.nova.catalog.outbox.OutboundEventType;
import com.grandis.nova.catalog.outbox.OutboxEvent;
import com.grandis.nova.catalog.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;

/**
 * 아웃박스 행을 봉투로 싸서 보내고 결과를 행에 남긴다. 커밋 직후 발행과 릴레이가 함께 쓴다.
 * 전송 실패는 삼킨다 — 행이 미발행으로 남아 릴레이가 다시 보낸다.
 * 봉투의 payload 는 저장된 JSON 을 트리로 읽어 싣는다 — 문자열로 실으면 받는 쪽에서 한 번 더 풀어야 한다.
 * 봉투도 웹 설정과 분리된 전용 매퍼로 쓴다(OutboxWriter 와 같은 이유).
 */
@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private static final JsonMapper ENVELOPE_JSON = JsonMapper.builder().build();

    private final OutboxEventRepository outboxEvents;
    private final MessageTransport transport;
    private final Clock clock;

    OutboxPublisher(OutboxEventRepository outboxEvents, MessageTransport transport, Clock clock) {
        this.outboxEvents = outboxEvents;
        this.transport = transport;
        this.clock = clock;
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
            outboxEvents.recordFailure(event.getId(), lease);
            log.warn("아웃박스 발행 실패 — 릴레이가 다시 보낸다 outboxEventId={} eventType={}", event.getId(), event.getEventType(), e);
            return false;
        }
        try {
            return outboxEvents.markPublished(event.getId(), clock.instant()) == 1;
        } catch (RuntimeException e) {
            // 보냈는데 표시만 못 했다 — 행이 미발행으로 남아 다시 보내진다(최소 한 번이라 허용, 받는 쪽은 eventId 로 중복을 안다).
            // 받는 쪽에서 중복이 보이면 이 로그로 원인을 찾는다. 실패로 기록해 실패 횟수가 실제 상태를 말하고 자기 리스를 풀어
            // 리스 만료(기본 1분)를 기다리지 않고 다음 주기에 다시 가져가게 한다. 예외는 삼켜 릴레이가 같은 묶음의 다음 행을 계속 보내게 한다
            log.warn("전송은 됐으나 발행 완료 표시 실패 — 다시 보내질 수 있다 outboxEventId={} eventId={}", event.getId(), event.getEventId(), e);
            try {
                outboxEvents.recordFailure(event.getId(), lease);
            } catch (RuntimeException recordError) {
                log.warn("발행 완료 표시 실패를 기록하지도 못했다 — 리스가 끝나면 다시 가져간다 outboxEventId={}", event.getId(), recordError);
            }
            return false;
        }
    }

    private OutboundMessage toMessage(OutboxEvent event) {
        OutboundEventType type = OutboundEventType.valueOf(event.getEventType());
        EventEnvelope envelope = new EventEnvelope(event.getEventId(), event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), event.getCreatedAt(), ENVELOPE_JSON.readTree(event.getPayload()));
        return new OutboundMessage(type.destination(), event.getEventId(), event.getEventType(),
                ENVELOPE_JSON.writeValueAsString(envelope));
    }
}
