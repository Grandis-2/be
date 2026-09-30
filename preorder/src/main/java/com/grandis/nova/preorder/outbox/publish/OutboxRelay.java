package com.grandis.nova.preorder.outbox.publish;

import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxEvent;
import com.grandis.nova.preorder.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 커밋 직후 발행이 실패했거나 그 사이 죽어 남은 행을 다시 보낸다.
 * 짧은 트랜잭션에서 오래된 자기 종류 행에 리스를 걸어 가져가고, 트랜잭션 밖에서 보낸다(잠금 · 커넥션을 쥐지 않는다).
 * 늘 실패하는 행이 relayBatch 건 넘게 쌓이면 뒤 행이 밀린다 — publish_attempts 로 드러난다.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final List<String> OWN_EVENT_TYPES = OutboundEventType.names();

    private final OutboxEventRepository outboxEvents;
    private final OutboxPublisher publisher;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    OutboxRelay(OutboxEventRepository outboxEvents, OutboxPublisher publisher, OutboxProperties properties,
                TransactionTemplate transactionTemplate, Clock clock) {
        this.outboxEvents = outboxEvents;
        this.publisher = publisher;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** @return 이번에 보낸 행 수 */
    @Scheduled(fixedDelayString = "${nova.outbox.relay-interval:10s}",
            initialDelayString = "${nova.outbox.relay-interval:10s}")
    public int relay() {
        Instant now = clock.instant();
        Instant lease = now.plus(properties.relayLease());
        List<OutboxEvent> events = transactionTemplate.execute(status -> claim(now, lease));
        int published = 0;
        int skipped = 0;
        for (OutboxEvent event : events) {
            // 보내기 직전에 내 리스인지 확인하며 연장한다. 연장한 리스 동안은 다른 인스턴스가 이 행을 가져가지 못한다
            Instant renewed = clock.instant().plus(properties.relayLease());
            if (outboxEvents.renewLease(event.getId(), lease, renewed) != 1) {
                skipped++;
                continue;
            }
            if (publisher.publish(event, renewed)) {
                published++;
            }
        }
        if (!events.isEmpty()) {
            log.info("아웃박스 재발행 대상={} 성공={} 리스를 잃어 건너뜀={}", events.size(), published, skipped);
        }
        return published;
    }

    private List<OutboxEvent> claim(Instant now, Instant lease) {
        List<OutboxEvent> events = outboxEvents.lockClaimable(
                now.minus(properties.relayAfter()), now, OWN_EVENT_TYPES, properties.relayBatch());
        if (events.isEmpty()) {
            return events;
        }
        int leased = outboxEvents.lease(events.stream().map(OutboxEvent::getId).toList(), lease);
        if (leased != events.size()) {
            // 잠근 행이라 어긋날 수 없다. 어긋나면 되돌리고 다음 주기에 다시 가져간다
            throw new IllegalStateException("잠근 아웃박스 행 " + events.size() + "건 중 " + leased + "건에만 리스를 걸었다");
        }
        return events;
    }
}
