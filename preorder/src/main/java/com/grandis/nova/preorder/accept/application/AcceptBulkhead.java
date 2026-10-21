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
import java.util.function.Function;
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
    private final Timer acquiredWait;
    private final Timer rejectedWait;
    private final MeterRegistry meterRegistry;
    /** 거절 건수의 상품 라벨 — 접수 중인 회차만 상품 id, 나머지는 하나로 묶는다. */
    private final Function<UUID, String> productTag;

    AcceptBulkhead(AcceptBulkheadProperties properties, int poolSize, MeterRegistry meterRegistry,
                   Function<UUID, String> productTag) {
        this.properties = properties;
        this.instancePermits = new Semaphore(properties.instanceLimit(poolSize), true);
        this.acquiredWait = waitTimer("acquired", meterRegistry);
        this.rejectedWait = waitTimer("rejected", meterRegistry);
        this.meterRegistry = meterRegistry;
        this.productTag = productTag;
        // 거절이 없어도 0 으로 내놓는다
        rejectedCounter("product", AcceptProductTags.OTHER);
        rejectedCounter("instance", AcceptProductTags.OTHER);
    }

    <T> T call(UUID productId, Supplier<T> work) {
        long started = System.nanoTime();
        long deadline = started + properties.maxWait().toNanos();
        Semaphore product = productPermits.get(productId, id -> new Semaphore(properties.productPermits(), true));
        if (!acquire(product, deadline)) {
            throw rejected("product", productId, started);
        }
        try {
            if (!acquire(instancePermits, deadline)) {
                throw rejected("instance", productId, started);
            }
            try {
                acquiredWait.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
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

    /** 기다린 시간은 거절도 따로 남긴다 — 허가를 얻은 요청만 세면 넘칠 때 대기가 낮게 보인다. */
    private DependencyUnavailableException rejected(String limit, UUID productId, long started) {
        rejectedWait.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        rejectedCounter(limit, productTag.apply(productId)).increment();
        long min = properties.retryAfterMin().toSeconds();
        long max = properties.retryAfterMax().toSeconds();
        return DependencyUnavailableException.overloaded(
                Duration.ofSeconds(ThreadLocalRandom.current().nextLong(min, max + 1)));
    }

    private Counter rejectedCounter(String limit, String product) {
        return meterRegistry.counter(REJECTED_METRIC, "limit", limit, AcceptProductTags.PRODUCT_TAG, product);
    }

    private static Timer waitTimer(String outcome, MeterRegistry meterRegistry) {
        return Timer.builder(WAIT_METRIC).tag("outcome", outcome).publishPercentileHistogram().register(meterRegistry);
    }
}
