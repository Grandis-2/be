package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.CampaignScheduleTouched;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.campaign.ScalingPlan;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 회차 일정에 맞춰 대기열 · preorder 의 최소 대수를 맞춘다. 주기마다 지금 상태 전체로 다시 계산하므로 놓친 변경 · 실패한 호출은 다음 주기에
 * 맞춰진다. 회차가 바뀌면 커밋 뒤 한 번 더 바로 돌린다(주기를 기다리지 않게). 태스크마다 돌아도 같은 값을 넣으므로 결과가 같다.
 */
class CampaignScalingReconciler {

    static final String FLOOR_METRIC = "preorder.scaling.floor";
    static final String FAILURE_METRIC = "preorder.scaling.failures";

    private static final Logger log = LoggerFactory.getLogger(CampaignScalingReconciler.class);

    private final Campaigns campaigns;
    private final ScalingProperties properties;
    private final CapacityFloors floors;
    private final Clock clock;
    private final Counter failures;
    private final AtomicInteger preorderFloor = new AtomicInteger();
    private final AtomicInteger waitingroomFloor = new AtomicInteger();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final ExecutorService touchExecutor =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("campaign-scaling").daemon().factory());

    CampaignScalingReconciler(Campaigns campaigns, ScalingProperties properties, CapacityFloors floors, Clock clock,
                              MeterRegistry meterRegistry) {
        this.campaigns = campaigns;
        this.properties = properties;
        this.floors = floors;
        this.clock = clock;
        this.failures = meterRegistry.counter(FAILURE_METRIC);
        Gauge.builder(FLOOR_METRIC, preorderFloor, AtomicInteger::get).tag("service", "preorder")
                .register(meterRegistry);
        Gauge.builder(FLOOR_METRIC, waitingroomFloor, AtomicInteger::get).tag("service", "waitingroom")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${nova.scaling.interval:1m}", initialDelayString = "${nova.scaling.interval:1m}")
    void scheduled() {
        reconcile();
    }

    /** 관리자 요청이 AWS 호출을 기다리지 않게 다른 스레드에서 돌린다. 이미 줄 서 있으면 하나로 합친다. */
    @TransactionalEventListener
    void onTouched(CampaignScheduleTouched touched) {
        if (queued.compareAndSet(false, true)) {
            touchExecutor.execute(() -> {
                queued.set(false);
                reconcile();
            });
        }
    }

    synchronized void reconcile() {
        ScalingFloors target;
        try {
            target = ScalingFloors.of(plans(clock.instant()), properties);
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("증설 대상 회차를 읽지 못했다 — 다음 주기에 다시 맞춘다", e);
            return;
        }
        if (target.capped()) {
            log.warn("필요한 최소 대수가 최대 대수를 넘어 최대로 맞춘다 — 인프라의 max 와 nova.scaling.*.max 를 올려야 한다");
        }
        preorderFloor.set(target.preorder());
        waitingroomFloor.set(target.waitingroom());
        apply(properties.preorder().service(), target.preorder());
        apply(properties.waitingroom().service(), target.waitingroom());
    }

    private List<ScalingPlan> plans(Instant now) {
        int limit = properties.limit();
        List<ScalingPlan> plans = campaigns.findScalingPlans(now, now.minus(properties.holdAfterOpen()),
                now.plus(properties.leadTime()), limit + 1);
        if (plans.size() <= limit) {
            return plans;
        }
        log.error("증설 대상 회차가 상한을 넘어 앞 {}건만 본다 — nova.scaling.limit 을 올려야 한다", limit);
        return plans.subList(0, limit);
    }

    /** 서비스마다 따로 — 한쪽이 실패해도 다른 쪽은 맞춘다. */
    private void apply(String service, int min) {
        try {
            floors.ensure(service, min);
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("최소 대수를 맞추지 못했다 — 다음 주기에 다시 맞춘다 service={} min={}", service, min, e);
        }
    }

    @PreDestroy
    void stop() {
        touchExecutor.shutdownNow();
    }
}
