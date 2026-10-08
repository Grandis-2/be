package com.grandis.nova.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 보존 기간이 지난 발행 완료 행을 지운다. 표가 끝없이 커지지 않게 한다 — 미발행 행은 릴레이 몫이라 건드리지 않는다.
 * 자기 스레드 하나에서 주기적으로 돌아 릴레이 · 미발행 집계를 막지 않는다. 여러 인스턴스가 함께 돌아도 지운 행은 다시 지워지지 않는다.
 * 한 문장에 묶음만큼 지우고(자동 커밋) 남은 만큼 되풀이한다 — 긴 트랜잭션 · 큰 잠금을 만들지 않는다.
 */
class OutboxCleaner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleaner.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final OutboxStore store;
    private final OutboxMetrics metrics;
    private final OutboxProperties properties;
    private final Clock clock;
    private ScheduledExecutorService executor;

    OutboxCleaner(OutboxStore store, OutboxMetrics metrics, OutboxProperties properties, Clock clock) {
        this.store = store;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return 이번에 지운 행 수 */
    int clean() {
        Instant cutoff = clock.instant().minus(properties.retention());
        int total = 0;
        int deleted;
        do {
            deleted = store.deletePublishedBefore(cutoff, properties.cleanupBatch());
            total += deleted;
        } while (deleted == properties.cleanupBatch() && !Thread.currentThread().isInterrupted());
        if (total > 0) {
            metrics.cleaned(total);
            log.info("보존 기간이 지난 발행 완료 아웃박스 행을 지웠다 count={} cutoff={}", total, cutoff);
        }
        return total;
    }

    /** 이미 돌고 있으면 그대로 둔다. 첫 정리는 한 주기 뒤에 한다 — 기동 직후의 DB 부하에 얹지 않는다. */
    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("outbox-cleanup").daemon().factory());
        long millis = properties.cleanupInterval().toMillis();
        executor.scheduleWithFixedDelay(this::cleanQuietly, millis, millis, TimeUnit.MILLISECONDS);
    }

    /** 지우던 묶음 하나만 마치고 멈춘다. 남은 행은 다음 기동 때 지운다. */
    @Override
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    /** 새는 예외는 남기고 삼킨다 — 밖으로 나가면 실행기가 다음 실행을 조용히 멈춘다. */
    private void cleanQuietly() {
        try {
            clean();
        } catch (RuntimeException e) {
            log.error("아웃박스 정리 실패 — 다음 주기에 다시 한다", e);
        }
    }
}
