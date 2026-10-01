package com.grandis.nova.common.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 릴레이 전용 실행기. 주기 실행은 한 번 실패해도 멈추지 않고(예외가 새면 다음 실행이 조용히 사라진다), 종료는 짧게 끝난다. */
class OutboxRelaySchedulerTest {

    static final Duration MINUTE = Duration.ofMinutes(1);

    @Test
    void 릴레이가_예외나_스택_넘침을_던져도_다음_주기에_다시_돈다() {
        OutboxRelay relay = mock(OutboxRelay.class);
        AtomicInteger calls = new AtomicInteger();
        willAnswer(invocation -> switch (calls.incrementAndGet()) {
            case 1 -> throw new IllegalStateException("db down");
            case 2 -> throw new StackOverflowError("깨진 행");
            default -> 0;
        }).given(relay).relay();
        OutboxRelayScheduler scheduler = new OutboxRelayScheduler(relay, OutboxMetrics.NONE, Duration.ofMillis(10), MINUTE);

        scheduler.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() >= 3);
        } finally {
            scheduler.stop();
        }
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void 다시_시작해도_릴레이는_하나만_돈다() throws Exception {
        OutboxRelay relay = mock(OutboxRelay.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        willAnswer(invocation -> {
            calls.incrementAndGet();
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(50);
            running.decrementAndGet();
            return 0;
        }).given(relay).relay();
        OutboxRelayScheduler scheduler = new OutboxRelayScheduler(relay, OutboxMetrics.NONE, Duration.ofMillis(1), MINUTE);

        scheduler.start();
        scheduler.start();
        try {
            // 실행기가 둘이면 겹치는 실행이 생길 만큼 여러 번 돌 때까지 기다린다
            await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() >= 5);
        } finally {
            scheduler.stop();
        }
        assertThat(maxRunning.get()).isEqualTo(1);
        assertThat(scheduler.isRunning()).isFalse();
    }

    /**
     * 종료는 진행 중인 릴레이에 멈추라고 알리고 보내던 한 건만 기다린다. 넘기면 인터럽트하고 곧 돌아간다 —
     * 리스 길이(1분)만큼 종료 단계를 붙잡아 웹 서버 종료 · DB 풀 닫기가 밀리지 않게.
     */
    @Test
    void 멈출_때는_보내던_한_건만_기다리고_넘기면_인터럽트한다() throws Exception {
        OutboxRelay relay = mock(OutboxRelay.class);
        CountDownLatch relaying = new CountDownLatch(1);
        willAnswer(invocation -> {
            relaying.countDown();
            Thread.sleep(Duration.ofSeconds(30));
            return 0;
        }).given(relay).relay();
        OutboxRelayScheduler scheduler = new OutboxRelayScheduler(relay, OutboxMetrics.NONE, Duration.ofMillis(10),
                Duration.ofMillis(200));
        scheduler.start();
        assertThat(relaying.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        scheduler.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        verify(relay).requestStop();
        assertThat(scheduler.isRunning()).isFalse();
    }
}
