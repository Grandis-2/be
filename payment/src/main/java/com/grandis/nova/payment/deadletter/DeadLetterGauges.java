package com.grandis.nova.payment.deadletter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 되돌리기를 기다리는 DLQ 메시지의 수 · 가장 오래된 나이 · 종류와 사유별 수(preorder StateGauges 의 DLQ 지표와 같은 방식).
 * 수집할 때마다 DB 를 묻지 않도록 주기적으로 세어 두고 게이지는 그 값을 읽는다. 여러 인스턴스가 같은 값을 내므로
 * 대시보드에서는 한 인스턴스 값만 본다. 내보내기는 운영의 OTel 자바 에이전트가 Micrometer 지표를 그대로 옮긴다.
 * 스케줄링은 주기 작업 설정(PaymentJobsConfig)이 켠다 — nova.payment.jobs.enabled=false 면 갱신하지 않아 게이지가 기동 때 값(0)에
 * 머문다. 운영에서는 작업을 끄지 않는다.
 */
@Component
class DeadLetterGauges {

    static final String WAITING_METRIC = "payment.dlq.waiting";
    static final String OLDEST_AGE_METRIC = "payment.dlq.waiting.oldest.age";
    static final String WAITING_BY_TYPE_METRIC = "payment.dlq.waiting.by_type";

    private final DeadLetterBacklog backlog;
    private final AtomicLong waiting = new AtomicLong();
    private final AtomicLong oldestWaitingSeconds = new AtomicLong();
    private final MultiGauge waitingByType;
    private final Clock clock;

    DeadLetterGauges(DeadLetterBacklog backlog, MeterRegistry meterRegistry, Clock clock) {
        this.backlog = backlog;
        this.clock = clock;
        // 오래 방치된 DLQ 를 경보로 드러낸다 — 쌓인 환불 요청은 되돌리거나 버리기 전까지 환불되지 않는다
        Gauge.builder(WAITING_METRIC, waiting, AtomicLong::get).register(meterRegistry);
        Gauge.builder(OLDEST_AGE_METRIC, oldestWaitingSeconds, AtomicLong::get)
                .baseUnit("seconds").register(meterRegistry);
        // 무엇이 쌓였는지(종류 · 사유). 0 이 아닌 칸만 낸다
        waitingByType = MultiGauge.builder(WAITING_BY_TYPE_METRIC).register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${nova.metrics.refresh-interval:30s}",
            initialDelayString = "${nova.metrics.refresh-interval:30s}")
    void refresh() {
        // 새 값을 다 센 뒤에 바꾼다 — 먼저 0 으로 비우면 그사이 수집에 0 이 잡힌다
        DeadLetterBreakdown breakdown = backlog.breakdown();
        waiting.set(breakdown.total().count());
        Instant oldestAt = breakdown.total().oldestAt();
        oldestWaitingSeconds.set(oldestAt == null ? 0
                : Math.max(0, Duration.between(oldestAt, clock.instant()).toSeconds()));
        waitingByType.register(breakdown.byKind().entrySet().stream()
                .map(entry -> MultiGauge.Row.of(Tags.of("eventType", entry.getKey().eventType(),
                        "reason", entry.getKey().reason()), entry.getValue()))
                .toList(), true);
    }
}
