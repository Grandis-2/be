package com.grandis.nova.worker.mock;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 최근 4건 중 절반이 실패 · 느리면 열리고, 0.2초(다시 열리면 0.4초) 뒤 스스로 반쯤 열려 3건을 시험한다. */
class MockCircuitTest {

    static final Duration FAST = Duration.ofMillis(10);

    final AtomicInteger opened = new AtomicInteger();
    MockCircuit circuit;

    @BeforeEach
    void setUp() {
        circuit = new MockCircuit(Settings.circuit(), Settings.SLOW_CALL, Clock.systemUTC(),
                config -> CircuitBreaker.of(MockCircuit.NAME, config), opened::incrementAndGet);
    }

    @Test
    void 일시_실패와_결과_모름이_절반을_넘으면_열리고_호출을_막는다() {
        record(Verdict.SUCCESS, Verdict.SUCCESS, Verdict.TRANSIENT, Verdict.UNKNOWN);

        assertThat(circuit.isOpen()).isTrue();
        assertThat(circuit.tryAcquire()).isFalse();
        assertThat(opened).hasValue(1);
        assertThat(circuit.openRemaining()).isPositive().isLessThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    void 외부가_거절한_요청과_설정_오류는_세지_않는다() {
        for (int i = 0; i < 20; i++) {
            record(Verdict.REJECTED, Verdict.MISCONFIGURED);
        }

        assertThat(circuit.isOpen()).isFalse();
        assertThat(circuit.openRemaining()).isZero();
    }

    @Test
    void 성공해도_느린_호출이_많으면_열린다() {
        for (int i = 0; i < 4; i++) {
            assertThat(circuit.tryAcquire()).isTrue();
            circuit.record(Verdict.SUCCESS, Settings.SLOW_CALL.plusMillis(50));
        }

        assertThat(circuit.isOpen()).isTrue();
    }

    @Test
    void 호출이_없어도_시간이_지나면_반쯤_열리고_시험_3건_중_2건이_성공하면_닫힌다() {
        record(Verdict.TRANSIENT, Verdict.TRANSIENT, Verdict.TRANSIENT, Verdict.TRANSIENT);

        await().atMost(Duration.ofSeconds(2)).until(circuit::isHalfOpen);
        assertThat(circuit.halfOpenCalls()).isEqualTo(3);
        record(Verdict.SUCCESS, Verdict.TRANSIENT, Verdict.SUCCESS);

        assertThat(circuit.isOpen()).isFalse();
        assertThat(circuit.isHalfOpen()).isFalse();
        assertThat(circuit.tryAcquire()).isTrue();
    }

    @Test
    void 시험이_실패하면_다시_열리고_열려_있는_시간이_두_배가_된다() {
        record(Verdict.TRANSIENT, Verdict.TRANSIENT, Verdict.TRANSIENT, Verdict.TRANSIENT);
        await().atMost(Duration.ofSeconds(2)).until(circuit::isHalfOpen);

        record(Verdict.UNKNOWN, Verdict.UNKNOWN, Verdict.SUCCESS);

        assertThat(circuit.isOpen()).isTrue();
        assertThat(opened).hasValue(2);
        assertThat(circuit.openRemaining()).isGreaterThan(Duration.ofMillis(200))
                .isLessThanOrEqualTo(Duration.ofMillis(400));
    }

    private void record(Verdict... verdicts) {
        for (Verdict verdict : verdicts) {
            assertThat(circuit.tryAcquire()).as("호출 허용").isTrue();
            circuit.record(verdict, FAST);
        }
    }
}
