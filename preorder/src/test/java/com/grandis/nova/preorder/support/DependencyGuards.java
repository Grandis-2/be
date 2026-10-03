package com.grandis.nova.preorder.support;

import com.grandis.nova.preorder.integration.DependencyGuard;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

/** 단위 테스트용 가드. 재시도 없이 한 번만 부르고, 회로 · 상한은 기본값이라 몇 번의 호출로는 열리지 않는다. */
public final class DependencyGuards {

    private DependencyGuards() {
    }

    public static DependencyGuard passThrough() {
        return new DependencyGuard(CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()), BulkheadRegistry.ofDefaults());
    }
}
