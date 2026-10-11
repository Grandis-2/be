package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.job.JobType;
import com.grandis.nova.worker.mock.ConcurrencyLimiter.Permit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 상한 6 · 시작 3 · 최소 2 · CANCEL 전용 1, 10초 창. p95 1초 미만 · 실패 5% 미만이면 +1, p95 0.15초 이상 · 실패 20% 이상이면 절반. */
class ConcurrencyLimiterTest {

    static final Duration NO_WAIT = Duration.ZERO;
    static final Duration FAST = Duration.ofMillis(20);
    static final Duration WINDOW = Duration.ofSeconds(10);

    final MutableClock clock = new MutableClock();
    final ConcurrencyLimiter limiter = new ConcurrencyLimiter(Settings.concurrency(), Settings.SLOW_CALL, clock);
    final List<Permit> held = new ArrayList<>();

    @Test
    void 자리_수까지만_잡히고_돌려주면_다시_잡힌다() {
        assertThat(acquire(JobType.CANCEL, 3)).isEqualTo(3);
        assertThat(limiter.tryAcquire(JobType.CANCEL, NO_WAIT)).isEmpty();
        assertThat(limiter.inUse()).isEqualTo(3);

        held.getFirst().close();
        held.getFirst().close();

        assertThat(limiter.inUse()).as("두 번 닫아도 한 번만 돌려준다").isEqualTo(2);
        assertThat(limiter.tryAcquire(JobType.CANCEL, NO_WAIT)).isPresent();
    }

    @Test
    void 등록은_CANCEL_전용_자리를_쓰지_못한다() {
        assertThat(acquire(JobType.REGISTER, 3)).as("자리 3 중 1 은 CANCEL 전용").isEqualTo(2);

        assertThat(limiter.tryAcquire(JobType.CANCEL, NO_WAIT)).isPresent();
        assertThat(limiter.allowed(JobType.REGISTER)).isEqualTo(2);
        assertThat(limiter.allowed(JobType.CANCEL)).isEqualTo(3);
    }

    @Test
    void 기다리는_동안_자리가_나면_잡는다() throws Exception {
        acquire(JobType.CANCEL, 3);
        Thread releaser = Thread.ofVirtual().start(() -> {
            sleep(Duration.ofMillis(100));
            held.getFirst().close();
        });

        Optional<Permit> permit = limiter.tryAcquire(JobType.CANCEL, Duration.ofSeconds(5));

        releaser.join();
        assertThat(permit).isPresent();
    }

    @Test
    void 자리를_다_쓰면서_빠르고_실패가_적으면_창마다_하나씩_늘고_상한을_넘지_않는다() {
        for (int expected = 4; expected <= 6; expected++) {
            fill();
            window(FAST, 20, 0);
            assertThat(limiter.limit()).isEqualTo(expected);
        }
        fill();
        window(FAST, 20, 0);
        assertThat(limiter.limit()).as("상한").isEqualTo(6);
    }

    /** 등록만 오면 CANCEL 전용 자리는 비어 있다. 그래도 등록 자리를 다 썼으면 늘린다. */
    @Test
    void 등록만으로_등록_자리를_다_써도_늘린다() {
        acquire(JobType.REGISTER, 2);

        window(FAST, 20, 0);

        assertThat(limiter.limit()).isEqualTo(4);
    }

    @Test
    void 자리가_남는데_늘리지_않는다() {
        acquire(JobType.CANCEL, 1);

        window(FAST, 20, 0);

        assertThat(limiter.limit()).isEqualTo(3);
    }

    @Test
    void 느리거나_실패가_많으면_절반으로_줄고_최소_아래로_내려가지_않는다() {
        window(Settings.SLOW_CALL.plusMillis(10), 20, 0);
        assertThat(limiter.limit()).as("3 의 절반은 1 이지만 최소가 2").isEqualTo(2);

        ConcurrencyLimiter failing = new ConcurrencyLimiter(Settings.concurrency(), Settings.SLOW_CALL, clock);
        fill(failing);
        record(failing, FAST, 20, 0);
        clock.advance(WINDOW);
        failing.record(FAST, false);
        assertThat(failing.limit()).isEqualTo(4);
        record(failing, FAST, 15, 5);
        clock.advance(WINDOW);
        failing.record(FAST, false);
        assertThat(failing.limit()).as("21건 중 5건 실패(23%)").isEqualTo(2);
    }

    @Test
    void 조금_느리거나_실패가_조금_있으면_그대로_둔다() {
        fill();
        window(Duration.ofMillis(120), 18, 2);

        assertThat(limiter.limit()).as("실패 10% — 늘리지도 줄이지도 않는다").isEqualTo(3);
    }

    @Test
    void 늘리는_폭과_줄이는_비율은_설정을_따른다() {
        ConcurrencyLimiter tuned = new ConcurrencyLimiter(new WorkerProperties.Concurrency(20, 1, 10, 2, 1,
                Duration.ofMillis(100), 40, WINDOW, Duration.ofSeconds(1), 5, 20, 3, 0.8), Settings.SLOW_CALL, clock);

        fill(tuned);
        record(tuned, FAST, 20, 0);
        clock.advance(WINDOW);
        tuned.record(FAST, false);
        assertThat(tuned.limit()).as("+3").isEqualTo(13);

        record(tuned, Settings.SLOW_CALL, 20, 0);
        clock.advance(WINDOW);
        tuned.record(Settings.SLOW_CALL, false);
        assertThat(tuned.limit()).as("× 0.8").isEqualTo(10);
    }

    @Test
    void 차단기가_열리면_최소로_줄인다() {
        fill();
        window(FAST, 20, 0);
        assertThat(limiter.limit()).isEqualTo(4);

        limiter.shrinkToMin();

        assertThat(limiter.limit()).isEqualTo(2);
    }

    /** 창 하나를 채우고 넘긴다. 마지막 기록이 창을 닫으며 자리 수를 조절한다. */
    private void window(Duration latency, int calls, int failures) {
        record(limiter, latency, calls - failures, failures);
        clock.advance(WINDOW);
        limiter.record(latency, false);
    }

    private static void record(ConcurrencyLimiter target, Duration latency, int successes, int failures) {
        for (int i = 0; i < successes; i++) {
            target.record(latency, false);
        }
        for (int i = 0; i < failures; i++) {
            target.record(latency, true);
        }
    }

    /** 지금 자리를 모두 잡았다가 놓는다 — 이번 창에서 자리를 다 썼다는 흔적만 남긴다. */
    private void fill() {
        fill(limiter);
    }

    private static void fill(ConcurrencyLimiter target) {
        List<Permit> permits = new ArrayList<>();
        Optional<Permit> permit;
        while ((permit = target.tryAcquire(JobType.CANCEL, NO_WAIT)).isPresent()) {
            permits.add(permit.get());
        }
        permits.forEach(Permit::close);
    }

    private int acquire(JobType type, int attempts) {
        int acquired = 0;
        for (int i = 0; i < attempts; i++) {
            Optional<Permit> permit = limiter.tryAcquire(type, NO_WAIT);
            if (permit.isPresent()) {
                held.add(permit.get());
                acquired++;
            }
        }
        return acquired;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
