package com.grandis.nova.preorder.metrics;

import com.grandis.nova.preorder.outbox.OutboxBacklog;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.Preorders;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DB 에서 세야 하는 상태 지표 — 예약 상태별 수, 미발행 아웃박스 수와 그 최대 실패 횟수. 수집할 때마다 DB 를 묻지 않도록
 * 주기적으로 세어 두고 게이지는 그 값을 읽는다. 여러 인스턴스가 같은 값을 내므로 대시보드에서는 한 인스턴스 값만 본다.
 */
@Component
class StateGauges {

    private final Preorders preorders;
    private final OutboxBacklog outboxBacklog;
    private final Map<PreorderStatus, AtomicLong> statusCounts = new EnumMap<>(PreorderStatus.class);
    private final AtomicLong unpublished = new AtomicLong();
    private final AtomicLong maxUnpublishedAttempts = new AtomicLong();

    StateGauges(Preorders preorders, OutboxBacklog outboxBacklog, MeterRegistry meterRegistry) {
        this.preorders = preorders;
        this.outboxBacklog = outboxBacklog;
        for (PreorderStatus status : PreorderStatus.values()) {
            AtomicLong count = new AtomicLong();
            statusCounts.put(status, count);
            Gauge.builder("preorder.status.count", count, AtomicLong::get)
                    .tag("status", status.name())
                    .register(meterRegistry);
        }
        Gauge.builder("preorder.outbox.unpublished", unpublished, AtomicLong::get).register(meterRegistry);
        Gauge.builder("preorder.outbox.unpublished.max.attempts", maxUnpublishedAttempts, AtomicLong::get)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${nova.metrics.refresh-interval:30s}",
            initialDelayString = "${nova.metrics.refresh-interval:30s}")
    void refresh() {
        Map<PreorderStatus, Long> counts = preorders.countByStatus();
        // 새 값을 다 센 뒤에 바꾼다 — 먼저 0 으로 비우면 그사이 수집에 0 이 잡힌다
        statusCounts.forEach((status, count) -> count.set(counts.getOrDefault(status, 0L)));
        unpublished.set(outboxBacklog.unpublishedCount());
        maxUnpublishedAttempts.set(outboxBacklog.maxUnpublishedAttempts());
    }
}
