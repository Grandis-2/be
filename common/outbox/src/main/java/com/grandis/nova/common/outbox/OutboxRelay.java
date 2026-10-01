package com.grandis.nova.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 커밋 직후 발행이 실패했거나 그 사이 죽어 남은 행을 다시 보낸다. 표가 서비스 것이라 종류로 거르지 않는다.
 * 짧은 트랜잭션에서 오래된 행에 리스를 걸어 가져가고, 트랜잭션 밖에서 보낸다(잠금 · 커넥션을 쥐지 않는다).
 * 늘 실패하는 행은 실패가 적은 행 뒤로 가 다른 행을 막지 않는다. 그런 행은 publish_attempts 로 드러난다.
 * 멈추라고 하면 보내던 행만 마치고, 남은 행의 리스를 풀어 다른 인스턴스가 곧바로 가져가게 한다.
 */
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final OutboxPublisher publisher;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    /**
     * 멈춤 요청마다 오르는 세대. 실행은 시작할 때의 세대를 쥐고 행마다 비교한다 — 종료 시간을 넘겨 남은 실행이
     * 다시 시작(resume) 뒤에 되살아나 새 실행과 나란히 돌지 않게.
     */
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean stopping;

    OutboxRelay(OutboxStore store, OutboxPublisher publisher, OutboxProperties properties,
                TransactionTemplate transactionTemplate, Clock clock) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 진행 중인 릴레이를 다음 행 경계에서 멈춘다. 다시 돌리려면 {@link #resume()}. */
    void requestStop() {
        stopping = true;
        generation.incrementAndGet();
    }

    void resume() {
        stopping = false;
    }

    /** @return 이번에 보낸 행 수 */
    int relay() {
        long started = generation.get();
        if (stopping) {
            return 0;
        }
        Instant now = clock.instant();
        Instant lease = now.plus(properties.relayLease());
        List<OutboxRow> rows = transactionTemplate.execute(status -> claim(now, lease));
        int published = 0;
        int skipped = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (stopping || generation.get() != started) {
                List<Long> rest = rows.subList(i, rows.size()).stream().map(OutboxRow::id).toList();
                log.info("아웃박스 릴레이를 멈춘다 — 보내지 않은 {}건의 리스를 푼다", store.releaseLeases(rest, lease));
                break;
            }
            OutboxRow row = rows.get(i);
            // 보내기 직전에 내 리스인지 확인하며 연장한다. 연장한 리스 동안은 다른 인스턴스가 이 행을 가져가지 못한다
            Instant renewed = clock.instant().plus(properties.relayLease());
            if (store.renewLease(row.id(), lease, renewed) != 1) {
                skipped++;
                continue;
            }
            if (publisher.publish(row, renewed)) {
                published++;
            }
        }
        if (!rows.isEmpty()) {
            log.info("아웃박스 재발행 대상={} 성공={} 리스를 잃어 건너뜀={}", rows.size(), published, skipped);
        }
        return published;
    }

    private List<OutboxRow> claim(Instant now, Instant lease) {
        List<OutboxRow> rows = store.lockClaimable(now.minus(properties.relayAfter()), now, properties.relayBatch());
        if (rows.isEmpty()) {
            return rows;
        }
        int leased = store.lease(rows.stream().map(OutboxRow::id).toList(), lease);
        if (leased != rows.size()) {
            // 잠근 행이라 어긋날 수 없다. 어긋나면 되돌리고 다음 주기에 다시 가져간다
            throw new IllegalStateException("잠근 아웃박스 행 " + rows.size() + "건 중 " + leased + "건에만 리스를 걸었다");
        }
        return rows;
    }
}
