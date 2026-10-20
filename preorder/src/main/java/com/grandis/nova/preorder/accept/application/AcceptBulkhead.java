package com.grandis.nova.preorder.accept.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.grandis.nova.preorder.integration.DependencyUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 접수 트랜잭션 앞의 허가 두 개 — 상품별, 그다음 인스턴스 전체. 상품 허가를 먼저 얻는다: 거꾸로면 한 상품의 대기가 전체 허가를
 * 쥐고 다른 상품을 막는다. 둘 다 공정(도착 순서)이고 기다림 상한을 넘으면 커넥션 없이 503 + Retry-After 다.
 *
 * 처리량을 늘리지는 않는다 — 넘친 접수가 커넥션을 쥐고 잠금을 기다려 풀을 말리는 것을 막아 다른 상품 · 조회를 지킨다.
 * 상한은 인스턴스마다다.
 */
class AcceptBulkhead {

    static final String WAIT_METRIC = "preorder.accept.bulkhead.wait";
    static final String REJECTED_METRIC = "preorder.accept.bulkhead.rejected";

    private final AcceptBulkheadProperties properties;
    /** 쓰는 요청이 없으면 사라진다(약한 참조). 시간으로 지우면 쓰는 중인 세마포어 대신 새 것이 생겨 상품 상한이 깨진다. */
    private final Cache<UUID, Semaphore> productPermits = Caffeine.newBuilder().weakValues().build();
    private final Semaphore instancePermits;
    private final Timer waitTimer;
    private final Counter productRejected;
    private final Counter instanceRejected;

    AcceptBulkhead(AcceptBulkheadProperties properties, int poolSize, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.instancePermits = new Semaphore(properties.instanceLimit(poolSize), true);
        this.waitTimer = Timer.builder(WAIT_METRIC).publishPercentileHistogram().register(meterRegistry);
        this.productRejected = meterRegistry.counter(REJECTED_METRIC, "limit", "product");
        this.instanceRejected = meterRegistry.counter(REJECTED_METRIC, "limit", "instance");
    }

    <T> T call(UUID productId, Supplier<T> work) {
        long started = System.nanoTime();
        long deadline = started + properties.maxWait().toNanos();
        Semaphore product = productPermits.get(productId, id -> new Semaphore(properties.productPermits(), true));
        if (!acquire(product, deadline)) {
            throw rejected(productRejected);
        }
        try {
            if (!acquire(instancePermits, deadline)) {
                throw rejected(instanceRejected);
            }
            try {
                waitTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                return work.get();
            } finally {
                instancePermits.release();
            }
        } finally {
            product.release();
        }
    }

    private static boolean acquire(Semaphore permits, long deadline) {
        try {
            return permits.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private DependencyUnavailableException rejected(Counter counter) {
        counter.increment();
        long min = properties.retryAfterMin().toSeconds();
        long max = properties.retryAfterMax().toSeconds();
        return new DependencyUnavailableException(Duration.ofSeconds(ThreadLocalRandom.current().nextLong(min, max + 1)));
    }
}
