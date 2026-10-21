package com.grandis.nova.preorder.metrics;

import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.campaign.OpenAlertBacklog;
import com.grandis.nova.preorder.campaign.OpenAlertBacklog.Pending;
import com.grandis.nova.preorder.deadletter.DeadLetterBacklog;
import com.grandis.nova.preorder.deadletter.DeadLetterSummary;
import com.grandis.nova.preorder.preorder.PaymentOverdue;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import com.grandis.nova.preorder.syncjob.SyncJobs;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DB 에서 세야 하는 상태 지표 — 예약 상태별 수, 되돌리기를 기다리는 DLQ · 끝나지 않은 오픈 알림 발송의 수와 가장 오래된 나이,
 * 결제 기한이 지난 예약 수, 접수 중인 회차 수, worker 가 포기한 외부 작업 수(DLQ 는 종류 · 사유별, 포기한 작업은 상품별로도 나눈다).
 * 수집할 때마다 DB 를 묻지 않도록 주기적으로 세어 두고 게이지는 그 값을 읽는다. 여러 인스턴스가 같은 값을 내므로
 * 대시보드에서는 한 인스턴스 값만 본다. 미발행 아웃박스 지표(preorder.outbox.*)는 common:outbox 가 릴레이 주기로 센다.
 */
@Component
class StateGauges {

    static final String ACCEPTING_CAMPAIGNS_METRIC = "nova.preorder.campaigns.accepting";
    static final String DEAD_LETTER_JOBS_METRIC = "preorder.sync.dead_letter";
    static final String DEAD_LETTER_JOBS_BY_PRODUCT_METRIC = "preorder.sync.dead_letter.by_product";
    static final String WAITING_DEAD_LETTERS_BY_TYPE_METRIC = "preorder.dlq.waiting.by_type";

    private final Preorders preorders;
    private final Map<PreorderStatus, AtomicLong> statusCounts = new EnumMap<>(PreorderStatus.class);
    private final DeadLetterBacklog deadLetterBacklog;
    private final AtomicLong waitingDeadLetters = new AtomicLong();
    private final AtomicLong oldestWaitingDeadLetterSeconds = new AtomicLong();
    private final MultiGauge waitingDeadLettersByType;
    private final OpenAlertBacklog openAlertBacklog;
    private final AtomicLong pendingOpenAlerts = new AtomicLong();
    private final AtomicLong oldestPendingOpenAlertSeconds = new AtomicLong();
    private final AtomicLong paymentOverdue = new AtomicLong();
    private final AtomicLong paymentOverdueBlocked = new AtomicLong();
    private final Campaigns campaigns;
    private final AtomicLong acceptingCampaigns = new AtomicLong();
    private final SyncJobs syncJobs;
    private final Map<SyncJobType, AtomicLong> deadLetterJobs = new EnumMap<>(SyncJobType.class);
    private final MultiGauge deadLetterJobsByProduct;
    private final Clock clock;

    StateGauges(Preorders preorders, DeadLetterBacklog deadLetterBacklog, OpenAlertBacklog openAlertBacklog,
                Campaigns campaigns, SyncJobs syncJobs, MeterRegistry meterRegistry, Clock clock) {
        this.preorders = preorders;
        this.deadLetterBacklog = deadLetterBacklog;
        this.openAlertBacklog = openAlertBacklog;
        this.campaigns = campaigns;
        this.syncJobs = syncJobs;
        this.clock = clock;
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
        // 무엇이 쌓였는지(종류 · 사유). 0 이 아닌 칸만 낸다
        waitingDeadLettersByType = MultiGauge.builder(WAITING_DEAD_LETTERS_BY_TYPE_METRIC).register(meterRegistry);
        // 발송은 표시한 주기 안에 끝난다 — 나이가 주기 몇 번을 넘게 자라면 묶음이 계속 실패하는 것이다
        Gauge.builder("preorder.open_alert.pending", pendingOpenAlerts, AtomicLong::get).register(meterRegistry);
        Gauge.builder("preorder.open_alert.pending.oldest.age", oldestPendingOpenAlertSeconds, AtomicLong::get)
                .baseUnit("seconds").register(meterRegistry);
        // 만료 작업이 꺼져 있어도 센다 — 켜기 전에 쌓인 수를 보고, 막힌 예약(주문 쪽 취소 거절)은 사람이 푼다
        Gauge.builder("preorder.expiry.overdue", paymentOverdue, AtomicLong::get).register(meterRegistry);
        Gauge.builder("preorder.expiry.blocked", paymentOverdueBlocked, AtomicLong::get).register(meterRegistry);
        // 업무 경보용 — 접수 중인 회차가 있는데 새 예약(nova.preorder.created)이 없으면 장애다
        Gauge.builder(ACCEPTING_CAMPAIGNS_METRIC, acceptingCampaigns, AtomicLong::get).register(meterRegistry);
        // 포기한 작업은 사람이 재처리 · 수동 완료해야 끝난다. 취소면 예약이 취소 중에 멈춰 있고 외부에 예약이 남았을 수 있다
        for (SyncJobType jobType : SyncJobType.values()) {
            AtomicLong count = new AtomicLong();
            deadLetterJobs.put(jobType, count);
            Gauge.builder(DEAD_LETTER_JOBS_METRIC, count, AtomicLong::get)
                    .tag("jobType", jobType.name())
                    .register(meterRegistry);
        }
        // 어느 상품이 막혔는지. 포기한 작업이 있는 상품만 내고 다 풀리면 지운다 — 시계열 수 = 문제 있는 상품 수.
        // 상품명은 바뀌면 시계열이 갈리므로 싣지 않는다(알림 쪽이 id 로 붙인다)
        deadLetterJobsByProduct = MultiGauge.builder(DEAD_LETTER_JOBS_BY_PRODUCT_METRIC).register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${nova.metrics.refresh-interval:30s}",
            initialDelayString = "${nova.metrics.refresh-interval:30s}")
    void refresh() {
        Map<PreorderStatus, Long> counts = preorders.countByStatus();
        // 새 값을 다 센 뒤에 바꾼다 — 먼저 0 으로 비우면 그사이 수집에 0 이 잡힌다
        statusCounts.forEach((status, count) -> count.set(counts.getOrDefault(status, 0L)));
        DeadLetterSummary deadLetters = deadLetterBacklog.summary();
        waitingDeadLetters.set(deadLetters.total().count());
        Instant oldestAt = deadLetters.total().oldestAt();
        oldestWaitingDeadLetterSeconds.set(oldestAt == null ? 0
                : Math.max(0, Duration.between(oldestAt, clock.instant()).toSeconds()));
        waitingDeadLettersByType.register(deadLetters.byKind().entrySet().stream()
                .map(entry -> MultiGauge.Row.of(Tags.of("eventType", entry.getKey().eventType(),
                        "reason", entry.getKey().reason()), entry.getValue()))
                .toList(), true);
        Pending pending = openAlertBacklog.pending();
        pendingOpenAlerts.set(pending.count());
        oldestPendingOpenAlertSeconds.set(pending.oldestAge().toSeconds());
        PaymentOverdue overdue = preorders.countPaymentOverdue(clock.instant());
        paymentOverdue.set(overdue.overdue());
        paymentOverdueBlocked.set(overdue.blocked());
        acceptingCampaigns.set(campaigns.countAccepting(clock.instant()));
        Map<SyncJobType, Map<UUID, Long>> deadLetterByProduct = syncJobs.countDeadLetterByProduct();
        deadLetterJobs.forEach((jobType, count) -> count.set(deadLetterByProduct.getOrDefault(jobType, Map.of())
                .values().stream().mapToLong(Long::longValue).sum()));
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        deadLetterByProduct.forEach((jobType, byProduct) -> byProduct.forEach((productId, count) -> rows.add(
                MultiGauge.Row.of(Tags.of("jobType", jobType.name(), "productId", productId.toString()), count))));
        deadLetterJobsByProduct.register(rows, true);
    }
}
