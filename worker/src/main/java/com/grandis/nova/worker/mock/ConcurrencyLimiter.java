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
 * 자리 수는 스스로 조절한다(AIMD): 창마다 빠르고 실패가 적으면 늘리고, 느리거나 실패가 많으면 크게 줄인다. 늘릴 때는 기준점까지
 * 두 배씩(slow start), 넘으면 조금씩 늘린다. 기준점은 처음엔 상한이고, 줄일 때마다 낮춘다 — 장애 뒤에도 빨리 돌아오되 다시 몰리지 않게.
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
    /** 두 배씩 늘리는 데까지(TCP 의 ssthresh). 처음엔 상한이라 첫 감소 전까지 두 배씩 는다. */
    private int threshold;
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
        this.threshold = max;
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

    /**
     * 차단기가 열렸다. 다시 열릴 때 조심스럽게 시작하도록 가장 적은 자리로 줄이고, 줄이기 전 자리의 절반까지는 두 배씩 돌아오게 한다.
     * 이미 최소면 기준점을 그대로 둔다 — 반쯤 열림 시험이 실패해 다시 열려도 장애 전의 기억을 잃지 않게.
     */
    void shrinkToMin() {
        lock.lock();
        try {
            if (limit != settings.min()) {
                threshold = Math.max(settings.min(), limit / 2);
                log.info("동시 요청 자리를 최소로 줄인다 {} → {} (두 배씩 {} 까지 돌아온다)", limit, settings.min(), threshold);
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
        boolean slow = p95.compareTo(slowCallThreshold) >= 0;
        boolean failing = failureRate >= settings.unhealthyFailureRate();
        boolean enoughCalls = latencies.size() >= settings.minimumCalls();
        boolean busy = peak >= allowed(JobType.REGISTER);
        // 호출이 적은 창은 한두 건으로 비율이 크게 흔들린다. 다만 느리면 끝나는 호출 자체가 줄므로, 자리를 다 쓴 채 느리면 줄인다
        if (failing && enoughCalls || slow && (enoughCalls || busy)) {
            limit = Math.max(settings.min(), (int) (limit * settings.decreaseFactor()));
            threshold = limit;
        } else if (!slow && !failing && p95.compareTo(settings.healthyP95()) < 0
                && failureRate < settings.healthyFailureRate() && busy && limit < max) {
            // 두 배는 기준점에서, 더하기는 상한에서 멈춘다 — 넘치지 않게 더하거나 곱하기 전에 견준다
            limit = limit >= threshold ? limit + Math.min(settings.increaseStep(), max - limit)
                    : limit > threshold / 2 ? threshold : limit * 2;
            freed.signalAll();
        }
        if (limit != before) {
            log.info("동시 요청 자리를 조절한다 {} → {} threshold={} p95={} failureRate={}% calls={}",
                    before, limit, threshold, p95, failureRate, latencies.size());
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
