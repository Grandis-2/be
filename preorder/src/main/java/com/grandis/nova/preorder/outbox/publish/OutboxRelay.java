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
        Instant leaseUntil = now.plus(properties.relayLease());
        List<OutboxEvent> events = transactionTemplate.execute(status -> claim(now, leaseUntil));
        int published = 0;
        for (int i = 0; i < events.size(); i++) {
            // 리스가 끝나면 다른 인스턴스가 같은 행을 가져갈 수 있다. 남은 행은 돌려놓고 다음 주기에 맡긴다
            if (!clock.instant().isBefore(leaseUntil)) {
                List<Long> rest = events.subList(i, events.size()).stream().map(OutboxEvent::getId).toList();
                outboxEvents.releaseLease(rest, leaseUntil);
                log.warn("아웃박스 재발행이 리스 안에 끝나지 않아 {}건을 돌려놓는다", rest.size());
                break;
            }
            if (publisher.publish(events.get(i), leaseUntil)) {
                published++;
            }
        }
        if (!events.isEmpty()) {
            log.info("아웃박스 재발행 대상={} 성공={}", events.size(), published);
        }
        return published;
    }

    private List<OutboxEvent> claim(Instant now, Instant leaseUntil) {
        List<OutboxEvent> events = outboxEvents.lockClaimable(
                now.minus(properties.relayAfter()), now, OWN_EVENT_TYPES, properties.relayBatch());
        if (!events.isEmpty()) {
            outboxEvents.lease(events.stream().map(OutboxEvent::getId).toList(), leaseUntil);
        }
        return events;
    }
}
