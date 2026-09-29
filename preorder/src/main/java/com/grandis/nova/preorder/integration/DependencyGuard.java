package com.grandis.nova.preorder.integration;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 다른 서비스 호출을 재시도(바깥) → 서킷 브레이커 → 동시 호출 상한 순으로 감싼다. 설정은 의존 서비스 이름별이다
 * (resilience4j.*.instances.&lt;이름&gt;). 회로가 열렸거나 상한이 차면 기다리지 않고 503 으로 돌려준다.
 */
@Component
public class DependencyGuard {

    /** 상한이 찼을 때 · 호출이 실패했을 때 다시 시도하라고 권하는 대기. 회로가 열렸을 때는 열림 유지 시간을 쓴다. */
    static final Duration SHORT_RETRY_AFTER = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(DependencyGuard.class);

    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final BulkheadRegistry bulkheads;

    public DependencyGuard(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries, BulkheadRegistry bulkheads) {
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.bulkheads = bulkheads;
    }

    /** @throws DependencyUnavailableException 회로가 열렸거나 동시 호출 상한이 찼다 */
    public <T> T call(String dependency, Supplier<T> call) {
        CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(dependency);
        Bulkhead bulkhead = bulkheads.bulkhead(dependency);
        Supplier<T> guarded = Retry.decorateSupplier(retries.retry(dependency),
                CircuitBreaker.decorateSupplier(circuitBreaker, Bulkhead.decorateSupplier(bulkhead, call)));
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            log.warn("{} 회로가 열려 호출하지 않는다", dependency);
            long openMillis = circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
            throw new DependencyUnavailableException(openMillis > 0 ? Duration.ofMillis(openMillis) : SHORT_RETRY_AFTER);
        } catch (BulkheadFullException e) {
            log.warn("{} 동시 호출 상한이 찼다", dependency);
            throw new DependencyUnavailableException(SHORT_RETRY_AFTER);
        }
    }
}
