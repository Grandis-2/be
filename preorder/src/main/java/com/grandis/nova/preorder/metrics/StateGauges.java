package com.grandis.nova.preorder.metrics;

import com.grandis.nova.preorder.campaign.OpenAlertBacklog;
import com.grandis.nova.preorder.campaign.OpenAlertBacklog.Pending;
import com.grandis.nova.preorder.deadletter.DeadLetterBacklog;
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
 * DB 에서 세야 하는 상태 지표 — 예약 상태별 수, 되돌리기를 기다리는 DLQ · 끝나지 않은 오픈 알림 발송의 수와 가장 오래된 나이.
 * 수집할 때마다 DB 를 묻지 않도록 주기적으로 세어 두고 게이지는 그 값을 읽는다. 여러 인스턴스가 같은 값을 내므로
 * 대시보드에서는 한 인스턴스 값만 본다. 미발행 아웃박스 지표(preorder.outbox.*)는 common:outbox 가 릴레이 주기로 센다.
 */
@Component
class StateGauges {

    private final Preorders preorders;
    private final Map<PreorderStatus, AtomicLong> statusCounts = new EnumMap<>(PreorderStatus.class);
    private final DeadLetterBacklog deadLetterBacklog;
    private final AtomicLong waitingDeadLetters = new AtomicLong();
    private final AtomicLong oldestWaitingDeadLetterSeconds = new AtomicLong();
    private final OpenAlertBacklog openAlertBacklog;
    private final AtomicLong pendingOpenAlerts = new AtomicLong();
    private final AtomicLong oldestPendingOpenAlertSeconds = new AtomicLong();

    StateGauges(Preorders preorders, DeadLetterBacklog deadLetterBacklog, OpenAlertBacklog openAlertBacklog,
                MeterRegistry meterRegistry) {
        this.preorders = preorders;
        this.deadLetterBacklog = deadLetterBacklog;
        this.openAlertBacklog = openAlertBacklog;
        for (PreorderStatus status : PreorderStatus.values()) {
            AtomicLong count = new AtomicLong();
            statusCounts.put(status, count);
            Gauge.builder("preorder.status.count", count, AtomicLong::get)
                    .tag("status", status.name())
                    .register(meterRegistry);
        }
        // 오래 방치된 DLQ 를 경보로 드러낸다
        Gauge.builder("preorder.dlq.waiting", waitingDeadLetters, AtomicLong::get).register(meterRegistry);
        Gauge.builder("preorder.dlq.waiting.oldest.age", oldestWaitingDeadLetterSeconds, AtomicLong::get)
                .baseUnit("seconds").register(meterRegistry);
        // 발송은 표시한 주기 안에 끝난다 — 나이가 주기 몇 번을 넘게 자라면 묶음이 계속 실패하는 것이다
        Gauge.builder("preorder.open_alert.pending", pendingOpenAlerts, AtomicLong::get).register(meterRegistry);
        Gauge.builder("preorder.open_alert.pending.oldest.age", oldestPendingOpenAlertSeconds, AtomicLong::get)
                .baseUnit("seconds").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${nova.metrics.refresh-interval:30s}",
            initialDelayString = "${nova.metrics.refresh-interval:30s}")
    void refresh() {
        Map<PreorderStatus, Long> counts = preorders.countByStatus();
        // 새 값을 다 센 뒤에 바꾼다 — 먼저 0 으로 비우면 그사이 수집에 0 이 잡힌다
        statusCounts.forEach((status, count) -> count.set(counts.getOrDefault(status, 0L)));
        waitingDeadLetters.set(deadLetterBacklog.waitingCount());
        oldestWaitingDeadLetterSeconds.set(deadLetterBacklog.oldestWaitingAge().toSeconds());
        Pending pending = openAlertBacklog.pending();
        pendingOpenAlerts.set(pending.count());
        oldestPendingOpenAlertSeconds.set(pending.oldestAge().toSeconds());
    }
}
