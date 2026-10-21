package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.CampaignScheduleTouched;
import com.grandis.nova.preorder.campaign.Campaigns;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 회차 일정에 맞춰 대기열 · preorder 의 최소 대수를 맞춘다. 주기마다 지금 상태 전체로 다시 계산하므로 놓친 변경 · 실패한 호출은 다음 주기에
 * 맞춰진다. 회차가 바뀌면 커밋 뒤 한 번 더 바로 돌린다. 맞추기는 전용 스레드 하나에서만 돈다 — AWS 가 늦어도 다른 주기 작업을 붙잡지 않는다.
 * 태스크 사이에서는 {@link ScalingLock} 으로 한 번에 하나다.
 */
class CampaignScalingReconciler {

    static final String FLOOR_METRIC = "preorder.scaling.floor";
    static final String FAILURE_METRIC = "preorder.scaling.failures";
    /** 다른 태스크가 맞추는 중이면 끝날 때까지 이 간격으로 다시 잡아 본다. 잠금 수명이 지나면 그만두고 다음 주기에 맞춘다. */
    static final Duration LOCK_RETRY = Duration.ofMillis(200);

    private static final Logger log = LoggerFactory.getLogger(CampaignScalingReconciler.class);

    private final Campaigns campaigns;
    private final ScalingProperties properties;
    private final CapacityFloors floors;
    private final ScalingLock lock;
    private final Clock clock;
    private final Counter failures;
    private final AtomicInteger preorderFloor = new AtomicInteger();
    private final AtomicInteger waitingroomFloor = new AtomicInteger();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("campaign-scaling").daemon().factory());

    CampaignScalingReconciler(Campaigns campaigns, ScalingProperties properties, CapacityFloors floors,
                              ScalingLock lock, Clock clock, MeterRegistry meterRegistry) {
        this.campaigns = campaigns;
        this.properties = properties;
        this.floors = floors;
        this.lock = lock;
        this.clock = clock;
        this.failures = meterRegistry.counter(FAILURE_METRIC);
        Gauge.builder(FLOOR_METRIC, preorderFloor, AtomicInteger::get).tag("service", "preorder")
                .register(meterRegistry);
        Gauge.builder(FLOOR_METRIC, waitingroomFloor, AtomicInteger::get).tag("service", "waitingroom")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${nova.scaling.interval:1m}", initialDelayString = "${nova.scaling.interval:1m}")
    void scheduled() {
        request();
    }

    /** 관리자 요청이 AWS 호출을 기다리지 않게 전용 스레드로 넘긴다. */
    @TransactionalEventListener
    void onTouched(CampaignScheduleTouched touched) {
        request();
    }

    /** 이미 줄 서 있으면 하나로 합친다. 도는 중에 들어온 요청은 끝난 뒤 한 번 더 돈다(그 사이 바뀐 값을 읽게). */
    private void request() {
        if (queued.compareAndSet(false, true)) {
            executor.execute(() -> {
                queued.set(false);
                reconcileExclusively();
            });
        }
    }

    void reconcileExclusively() {
        String token = acquire();
        try {
            reconcile();
        } finally {
            if (token != null) {
                release(token);
            }
        }
    }

    /** 잠금을 못 잡으면 null — Redis 가 안 되면 잠금 없이라도 맞춘다(덮어쓰기는 다음 주기에 바로잡힌다). */
    private String acquire() {
        Instant giveUpAt = clock.instant().plus(properties.lockTtl());
        try {
            for (String token = lock.tryAcquire(); ; token = lock.tryAcquire()) {
                if (token != null) {
                    return token;
                }
                if (!clock.instant().isBefore(giveUpAt)) {
                    log.warn("다른 태스크가 맞추기 잠금을 오래 쥐고 있어 잠금 없이 맞춘다");
                    return null;
                }
                Thread.sleep(LOCK_RETRY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            log.warn("맞추기 잠금을 잡지 못해 잠금 없이 맞춘다", e);
            return null;
        }
    }

    private void release(String token) {
        try {
            lock.release(token);
        } catch (RuntimeException e) {
            log.warn("맞추기 잠금을 풀지 못했다 — 수명이 지나면 풀린다", e);
        }
    }

    private void reconcile() {
        ScalingFloors target;
        try {
            Instant now = clock.instant();
            target = ScalingFloors.of(campaigns.findScalingDemand(now, now.minus(properties.holdAfterOpen()),
                    now.plus(properties.leadTime())), properties);
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
        executor.shutdownNow();
    }
}
