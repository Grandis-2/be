package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 동시에 나가 있는 요청 수를 묶는 벌크헤드. mock 은 몰려도 거절하지 않고 느려지기만 해서, 자리 수를 묶으면 느려질수록 덜 보낸다.
 * 자리 수는 스스로 조절한다(AIMD): 창마다 빠르고 실패가 적으면 조금 늘리고, 느리거나 실패가 많으면 크게 줄인다.
 * 자리 일부는 CANCEL 전용이다 — 등록 · 드로우가 밀려도 취소는 나간다.
 */
public final class ConcurrencyLimiter {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLimiter.class);

    /** 잡은 자리. 차례가 끝나면 닫는다. 두 번 닫아도 한 번만 돌려준다. */
    public interface Permit extends AutoCloseable {

        @Override
        void close();
    }

    private final WorkerProperties.Concurrency settings;
    private final Duration slowCallThreshold;
    private final Clock clock;
    private final int max;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition freed = lock.newCondition();
    private final List<Long> latencies = new ArrayList<>();
    private volatile int limit;
    private volatile int inUse;
    private int failures;
    /** 이번 창에서 가장 많이 쓴 자리 수. 등록이 쓸 수 있는 만큼도 못 채웠으면 늘리지 않는다. */
    private int peak;
    private Instant windowStart;

    ConcurrencyLimiter(WorkerProperties.Concurrency settings, Duration slowCallThreshold, Clock clock) {
        this.settings = settings;
        this.slowCallThreshold = slowCallThreshold;
        this.clock = clock;
        this.max = settings.limit();
        this.limit = settings.initial();
        this.windowStart = clock.instant();
    }

    /** 자리를 잡는다. wait 안에 나지 않으면 비어 있는 값을 돌려준다. CANCEL 이 아니면 CANCEL 전용 자리를 쓰지 못한다. */
    public Optional<Permit> tryAcquire(JobType type, Duration wait) {
        long nanos = wait.toNanos();
        lock.lock();
        try {
            while (inUse >= allowed(type)) {
                if (nanos <= 0) {
                    return Optional.empty();
                }
                nanos = freed.awaitNanos(nanos);
            }
            inUse++;
            peak = Math.max(peak, inUse);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            lock.unlock();
        }
        AtomicBoolean closed = new AtomicBoolean();
        return Optional.of(() -> {
            if (closed.compareAndSet(false, true)) {
                release();
            }
        });
    }

    /** 이 종류의 작업이 쓸 수 있는 자리 수. */
    public int allowed(JobType type) {
        return type == JobType.CANCEL ? limit : limit - settings.cancelReserved();
    }

    public int limit() {
        return limit;
    }

    public int inUse() {
        return inUse;
    }

    /** 호출 한 번의 지연과 성패를 알린다. 창이 끝났으면 자리 수를 조절한다. */
    void record(Duration elapsed, boolean failed) {
        lock.lock();
        try {
            latencies.add(elapsed.toNanos());
            if (failed) {
                failures++;
            }
            Instant now = clock.instant();
            if (!now.isBefore(windowStart.plus(settings.adjustEvery()))) {
                adjust();
                startWindow(now);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 차단기가 열렸다. 다시 열릴 때 조심스럽게 시작하도록 가장 적은 자리로 줄인다. */
    void shrinkToMin() {
        lock.lock();
        try {
            if (limit != settings.min()) {
                log.info("동시 요청 자리를 최소로 줄인다 {} → {}", limit, settings.min());
                limit = settings.min();
            }
            startWindow(clock.instant());
        } finally {
            lock.unlock();
        }
    }

    private void adjust() {
        if (latencies.isEmpty()) {
            return;
        }
        Collections.sort(latencies);
        Duration p95 = Duration.ofNanos(latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1));
        int failureRate = failures * 100 / latencies.size();
        int before = limit;
        if (p95.compareTo(slowCallThreshold) >= 0 || failureRate >= settings.unhealthyFailureRate()) {
            limit = Math.max(settings.min(), (int) (limit * settings.decreaseFactor()));
        } else if (p95.compareTo(settings.healthyP95()) < 0 && failureRate < settings.healthyFailureRate()
                && peak >= allowed(JobType.REGISTER) && limit < max) {
            limit = Math.min(max, limit + settings.increaseStep());
            freed.signalAll();
        }
        if (limit != before) {
            log.info("동시 요청 자리를 조절한다 {} → {} p95={} failureRate={}% calls={}",
                    before, limit, p95, failureRate, latencies.size());
        }
    }

    private void startWindow(Instant now) {
        latencies.clear();
        failures = 0;
        peak = inUse;
        windowStart = now;
    }

    private void release() {
        lock.lock();
        try {
            inUse--;
            freed.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
