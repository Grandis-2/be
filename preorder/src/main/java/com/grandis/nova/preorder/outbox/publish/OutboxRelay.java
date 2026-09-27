package com.grandis.nova.preorder.outbox.publish;

import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxEvent;
import com.grandis.nova.preorder.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 커밋 직후 발행이 실패했거나 그 사이 죽어 남은 행을 다시 보낸다.
 * 오래된 자기 종류 행만 SKIP LOCKED 로 잠가 가져가므로 여러 인스턴스가 같은 행을 집지 않는다.
 * 늘 실패하는 행이 relayBatch 건 넘게 쌓이면 뒤 행이 밀린다 — publish_attempts 로 드러난다.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final List<String> OWN_EVENT_TYPES = OutboundEventType.names();

    private final OutboxEventRepository outboxEvents;
    private final OutboxPublisher publisher;
    private final OutboxProperties properties;
    private final Clock clock;

    OutboxRelay(OutboxEventRepository outboxEvents, OutboxPublisher publisher, OutboxProperties properties,
                Clock clock) {
        this.outboxEvents = outboxEvents;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return 이번에 보낸 행 수 */
    @Scheduled(fixedDelayString = "${nova.outbox.relay-interval:10s}",
            initialDelayString = "${nova.outbox.relay-interval:10s}")
    @Transactional
    public int relay() {
        List<OutboxEvent> events = outboxEvents.lockUnpublished(
                clock.instant().minus(properties.relayAfter()), OWN_EVENT_TYPES, properties.relayBatch());
        int published = (int) events.stream().filter(publisher::publish).count();
        if (!events.isEmpty()) {
            log.info("아웃박스 재발행 대상={} 성공={}", events.size(), published);
        }
        return published;
    }
}
