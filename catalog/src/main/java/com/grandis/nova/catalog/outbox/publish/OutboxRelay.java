package com.grandis.nova.catalog.outbox.publish;

import com.grandis.nova.catalog.outbox.OutboxEvent;
import com.grandis.nova.catalog.outbox.OutboxEventRepository;
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
 * 짧은 트랜잭션에서 오래된 행에 리스를 걸어 가져가고, 트랜잭션 밖에서 보낸다(잠금 · 커넥션을 쥐지 않는다) — preorder 의 릴레이와 같은 방식.
 * 늘 실패하는 행은 실패가 적은 행 뒤로 가 다른 행을 막지 않는다. 그런 행은 publish_attempts 로 드러난다.
 *
 * catalog 에는 지표 수집(actuator)이 없어 미발행 현황을 주기마다 경고 로그로 낸다 — 미발행 행이 남아 있으면
 * "미발행 N건 · 최다 실패 M회" 가 찍힌다. 등록한 상품이 준비 전에 머물 때 받는 쪽 거절(DLQ)과 catalog 가 아직 못 보낸 것을 이것으로 가른다.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

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
    @Scheduled(fixedDelayString = "${nova.outbox.relay-interval:10s}", initialDelayString = "${nova.outbox.relay-interval:10s}")
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
        warnIfBacklog();
        return published;
    }

    /** 미발행 행이 남아 있으면 경고한다. 조건이 published_at IS NULL 이라 published_at 이 맨 앞인 인덱스(ix_catalog_outbox_unpublished)를 쓸 수 있다. */
    private void warnIfBacklog() {
        long unpublished = outboxEvents.countUnpublished();
        if (unpublished > 0) {
            log.warn("catalog 아웃박스 미발행 {}건 · 최다 실패 {}회 — 큐 권한 · 큐 장애를 확인한다", unpublished, outboxEvents.maxUnpublishedAttempts());
        }
    }

    private List<OutboxEvent> claim(Instant now, Instant lease) {
        List<OutboxEvent> events = outboxEvents.lockClaimable(now.minus(properties.relayAfter()), now, properties.relayBatch());
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
