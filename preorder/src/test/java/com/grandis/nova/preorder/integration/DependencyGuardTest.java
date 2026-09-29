package com.grandis.nova.preorder.integration;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 재시도(바깥) → 서킷 브레이커 → 동시 호출 상한. 운영 값 대신 작게 줄인 설정으로 동작만 본다. */
class DependencyGuardTest {

    static final String DEPENDENCY = "catalog";
    static final Duration OPEN_WAIT = Duration.ofMillis(200);

    final CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(4)
            .minimumNumberOfCalls(4)
            .failureRateThreshold(50)
            .slowCallDurationThreshold(Duration.ofMillis(50))
            .slowCallRateThreshold(50)
            .waitDurationInOpenState(OPEN_WAIT)
            .permittedNumberOfCallsInHalfOpenState(1)
            .ignoreExceptions(HttpClientErrorException.class)
            .build());
    final DependencyGuard guard = new DependencyGuard(circuitBreakers,
            RetryRegistry.of(RetryConfig.custom()
                    .maxAttempts(2)
                    .waitDuration(Duration.ofMillis(1))
                    .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                    .ignoreExceptions(HttpClientErrorException.class)
                    .build()),
            BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build()));

    @Test
    void 연결_실패는_한_번_다시_부르고_성공하면_값을_돌려준다() {
        AtomicInteger calls = new AtomicInteger();

        String answer = guard.call(DEPENDENCY, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new ResourceAccessException("connection refused");
            }
            return "ok";
        });

        assertThat(answer).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void 클라이언트_오류_4xx_는_다시_부르지_않고_회로의_실패로_세지_않는다() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard.call(DEPENDENCY, () -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad", null, null, null);
        })).isInstanceOf(HttpClientErrorException.class);

        assertThat(calls).hasValue(1);
        assertThat(circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void 실패가_쌓여_회로가_열리면_부르지_않고_열림_시간을_Retry_After_로_알린다() {
        failTwice();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard.call(DEPENDENCY, counting(calls)))
                .isInstanceOfSatisfying(DependencyUnavailableException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(OPEN_WAIT));
        assertThat(calls).hasValue(0);
    }

    @Test
    void 열림_시간이_지나면_반열림에서_한_번_시험해_성공하면_닫힌다() {
        failTwice();
        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        await().atMost(Duration.ofSeconds(2)).pollDelay(OPEN_WAIT).untilAsserted(() ->
                assertThat(guard.call(DEPENDENCY, () -> "ok")).isEqualTo("ok"));

        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 느린_호출도_실패로_세어_회로를_연다() {
        for (int i = 0; i < 4; i++) {
            guard.call(DEPENDENCY, () -> {
                sleep(Duration.ofMillis(60));
                return "slow";
            });
        }

        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 동시_호출_상한이_차면_기다리지_않고_바로_503() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> holding = CompletableFuture.supplyAsync(() -> guard.call(DEPENDENCY, () -> {
            entered.countDown();
            awaitQuietly(release);
            return "held";
        }));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> guard.call(DEPENDENCY, () -> "second"))
                    .isInstanceOfSatisfying(DependencyUnavailableException.class,
                            e -> assertThat(e.retryAfter()).isEqualTo(DependencyGuard.SHORT_RETRY_AFTER));
        } finally {
            release.countDown();
        }
        assertThat(holding.get(5, TimeUnit.SECONDS)).isEqualTo("held");
    }

    /** 재시도까지 실패하는 호출 두 번 = 회로에 실패 4건(창 4, 절반 이상 실패). */
    private void failTwice() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> guard.call(DEPENDENCY, () -> {
                throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", null, null, null);
            })).isInstanceOf(HttpServerErrorException.class);
        }
    }

    private CircuitBreaker circuitBreaker() {
        return circuitBreakers.circuitBreaker(DEPENDENCY);
    }

    private static Supplier<String> counting(AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            return "called";
        };
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
