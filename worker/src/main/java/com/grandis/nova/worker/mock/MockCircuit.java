package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.config.WorkerProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 외부 mock 차단기. worker 마다 따로 판단한다. 일시 실패 · 결과 모름만 실패로 세고, 외부가 거절한 요청 · 설정 오류 · 느린 성공은 세지 않는다.
 * 열려 있는 시간은 다시 열릴 때마다 늘고, 시간이 지나면 스스로 반쯤 열려 시험 호출을 받는다.
 */
public final class MockCircuit {

    private static final Logger log = LoggerFactory.getLogger(MockCircuit.class);
    static final String NAME = "mock";

    private final CircuitBreaker breaker;
    private final Clock clock;
    private final Duration openFirst;
    private final Duration openMax;
    private final double openMultiplier;
    private final int halfOpenCalls;
    /** 닫히지 않고 연달아 열린 횟수. 열려 있을 시간을 정한다. */
    private final AtomicInteger consecutiveOpens = new AtomicInteger();
    private volatile Instant openUntil = Instant.EPOCH;

    /**
     * @param create 설정으로 차단기를 만든다. 지표를 내려면 레지스트리가 만들게 한다
     * @param onOpen 열릴 때마다 불린다
     */
    MockCircuit(WorkerProperties.Circuit settings, Clock clock, Function<CircuitBreakerConfig, CircuitBreaker> create,
                Runnable onOpen) {
        this.clock = clock;
        this.openFirst = settings.openFirst();
        this.openMax = settings.openMax();
        this.openMultiplier = settings.openMultiplier();
        this.halfOpenCalls = settings.halfOpenCalls();
        this.breaker = create.apply(CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.window())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRate())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                .waitIntervalFunctionInOpenState(opens -> openFor(opens).toMillis())
                // 열린 동안은 받기를 멈춰 호출이 없다. 호출이 와야 반쯤 열리게 두면 영영 열려 있는다
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
        breaker.getEventPublisher().onStateTransition(event -> {
            switch (event.getStateTransition().getToState()) {
                case OPEN -> {
                    Duration wait = openFor(consecutiveOpens.incrementAndGet());
                    openUntil = clock.instant().plus(wait);
                    log.warn("외부 mock 차단기가 열렸다 — {} 동안 호출 · 받기를 멈춘다 consecutiveOpens={}",
                            wait, consecutiveOpens.get());
                    onOpen.run();
                }
                case CLOSED -> {
                    consecutiveOpens.set(0);
                    log.info("외부 mock 차단기가 닫혔다");
                }
                default -> {
                }
            }
        });
    }

    /** 호출해도 되는가. true 면 {@link #record} 로 결과를 알려야 한다(반쯤 열린 동안의 시험 자리를 잡고 있다). */
    boolean tryAcquire() {
        return breaker.tryAcquirePermission();
    }

    /** 자리를 잡았지만 호출하지 않았다. */
    void release() {
        breaker.releasePermission();
    }

    /**
     * 걸린 시간은 넘기지 않는다 — 느리기만 한 mock 에 열고 닫기를 되풀이하면 처리량이 0 이 된다. 느림은 자동 조절이 다룬다.
     * 응답 제한은 읽기마다 새로 재므로 한 호출이 끝내 걸리는 시간에는 상한이 없어, 문턱을 길게 두는 것으로는 막지 못한다.
     */
    void record(Verdict verdict) {
        switch (verdict) {
            case SUCCESS, ABSENT, PENDING -> breaker.onSuccess(0, TimeUnit.NANOSECONDS);
            case TRANSIENT, UNKNOWN -> breaker.onError(0, TimeUnit.NANOSECONDS, new IllegalStateException(verdict.name()));
            case REJECTED, MISCONFIGURED, NOT_CALLED -> breaker.releasePermission();
        }
    }

    public boolean isOpen() {
        return breaker.getState() == CircuitBreaker.State.OPEN;
    }

    public boolean isHalfOpen() {
        return breaker.getState() == CircuitBreaker.State.HALF_OPEN;
    }

    /** 반쯤 열린 동안 시험으로 받는 호출 수. */
    public int halfOpenCalls() {
        return halfOpenCalls;
    }

    /** 열려 있을 남은 시간. 열려 있지 않으면 0. */
    public Duration openRemaining() {
        if (!isOpen()) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(clock.instant(), openUntil);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private Duration openFor(int opens) {
        double millis = openFirst.toMillis() * Math.pow(openMultiplier, Math.max(opens, 1) - 1);
        return millis >= openMax.toMillis() ? openMax : Duration.ofMillis((long) millis);
    }
}
