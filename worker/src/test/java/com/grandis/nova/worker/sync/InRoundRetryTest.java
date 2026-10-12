package com.grandis.nova.worker.sync;

import com.grandis.nova.worker.mock.MockResult;
import com.grandis.nova.worker.mock.Verdict;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 한 차례 안에서 곧바로 다시 보내는 조건. 쉬는 시간은 0.2~0.5초(난수 0 이면 0.2초)다. */
class InRoundRetryTest {

    static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");
    static final MockResult RETRYABLE = result(Verdict.TRANSIENT, true);
    static final MockResult SUCCESS = result(Verdict.SUCCESS, false);

    final GenericApplicationContext context = new GenericApplicationContext();
    final ShutdownSignal shutdown = new ShutdownSignal(context);
    final InRoundRetry inRound = new InRoundRetry(new RetrySchedule(RetryScheduleTest.SETTINGS, () -> 0.0),
            RetryScheduleTest.SETTINGS, shutdown);
    final AtomicInteger calls = new AtomicInteger();

    @Test
    void 곧바로_다시_보내도_되는_실패면_정해진_횟수만큼_다시_부른다() {
        MockResult last = inRound.from(RETRYABLE, () -> {
            calls.incrementAndGet();
            return RETRYABLE;
        });

        assertThat(calls).hasValue(1);
        assertThat(last).isSameAs(RETRYABLE);
    }

    @Test
    void 다시_보내_성공하면_그_결과로_끝낸다() {
        assertThat(inRound.from(RETRYABLE, () -> SUCCESS)).isSameAs(SUCCESS);
    }

    @Test
    void 곧바로_다시_보낼_실패가_아니면_부르지_않는다() {
        MockResult unknown = result(Verdict.UNKNOWN, false);

        assertThat(inRound.from(unknown, () -> {
            calls.incrementAndGet();
            return SUCCESS;
        })).isSameAs(unknown);
        assertThat(calls).hasValue(0);
    }

    /** 방금 실패로 차단기가 열려 부르지 못했다. 이미 한 번 부른 차례이므로 앞선 실패가 이 차례의 결과다. */
    @Test
    void 다시_보내려는데_부르지_못하면_앞선_실패로_끝낸다() {
        assertThat(inRound.from(RETRYABLE, () -> result(Verdict.NOT_CALLED, false))).isSameAs(RETRYABLE);
    }

    @Test
    void 내려가는_중이면_다시_부르지_않는다() {
        shutdown.onApplicationEvent(new ContextClosedEvent(context));

        assertThat(inRound.from(RETRYABLE, () -> {
            calls.incrementAndGet();
            return SUCCESS;
        })).isSameAs(RETRYABLE);
        assertThat(calls).hasValue(0);
    }

    @Test
    void 다른_컨텍스트가_닫히는_것은_내려가는_것이_아니다() {
        shutdown.onApplicationEvent(new ContextClosedEvent(new GenericApplicationContext()));

        assertThat(shutdown.stopping()).isFalse();
    }

    private static MockResult result(Verdict verdict, boolean retryNow) {
        return new MockResult(verdict, null, null, null, null, null, retryNow, null, NOW, NOW);
    }
}
