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
    /** 멈추라는 요청. 묶음 사이에서 보고 다음 묶음을 시작하지 않는다. */
    private volatile boolean stopping;

    OutboxCleaner(OutboxStore store, OutboxMetrics metrics, OutboxProperties properties, Clock clock) {
        this.store = store;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 지운 수는 묶음마다 기록한다 — 중간 묶음이 실패해도 앞서 지운 행이 지표에 남는다.
     *
     * @return 이번에 지운 행 수
     */
    int clean() {
        Instant cutoff = clock.instant().minus(properties.retention());
        int total = 0;
        int deleted;
        try {
            do {
                deleted = store.deletePublishedBefore(cutoff, properties.cleanupBatch());
                if (deleted > 0) {
                    metrics.cleaned(deleted);
                    total += deleted;
                }
            } while (deleted == properties.cleanupBatch() && !stopping && !Thread.currentThread().isInterrupted());
        } finally {
            if (total > 0) {
                log.info("보존 기간이 지난 발행 완료 아웃박스 행을 지웠다 count={} cutoff={}", total, cutoff);
            }
        }
        return total;
    }

    /** 이미 돌고 있으면 그대로 둔다. 첫 정리는 한 주기 뒤에 한다 — 기동 직후의 DB 부하에 얹지 않는다. */
    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        stopping = false;
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("outbox-cleanup").daemon().factory());
        long millis = properties.cleanupInterval().toMillis();
        executor.scheduleWithFixedDelay(this::cleanQuietly, millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * 지우던 묶음 하나만 마치고 멈춘다. 남은 행은 다음 기동 때 지운다.
     * 그 묶음이 종료 시간을 넘기면 인터럽트한다 — 종료 단계(DB 풀 닫기)를 오래 붙잡지 않게.
     */
    @Override
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        stopping = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("아웃박스 정리가 종료 시간 안에 끝나지 않아 인터럽트한다");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    /** 새는 예외는 실패 지표 · 로그로 남기고 삼킨다 — 밖으로 나가면 실행기가 다음 실행을 조용히 멈춘다. */
    private void cleanQuietly() {
        try {
            clean();
        } catch (RuntimeException e) {
            metrics.cleanupFailed();
            log.error("아웃박스 정리 실패 — 다음 주기에 다시 한다", e);
        }
    }
}
