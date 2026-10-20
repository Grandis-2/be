package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.job.JobType;
import com.grandis.nova.worker.mock.ConcurrencyLimiter.Permit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 상한 6 · 시작 3 · 최소 2 · CANCEL 전용 1, 10초 창. p95 1초 미만 · 실패 5% 미만이면 늘리고(첫 감소 전까지 두 배, 그 뒤 +1),
 * p95 0.15초 이상 · 실패 20% 이상이면 절반(창에 10건 이상일 때만).
 */
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
    void 드로우도_CANCEL_전용_자리를_쓰지_못하고_등록과_자리를_나눠_쓴다() {
        assertThat(acquire(JobType.DRAW, 3)).isEqualTo(2);

        assertThat(limiter.tryAcquire(JobType.REGISTER, NO_WAIT)).as("등록 · 드로우가 같은 자리를 쓴다").isEmpty();
        assertThat(limiter.tryAcquire(JobType.CANCEL, NO_WAIT)).isPresent();
    }

    @Test
    void 기다리는_동안_자리가_나면_잡는다() throws Exception {
        acquire(JobType.CANCEL, 3);
        AtomicReference<Optional<Permit>> waited = new AtomicReference<>();
        Thread waiter = Thread.ofPlatform().start(
                () -> waited.set(limiter.tryAcquire(JobType.CANCEL, Duration.ofSeconds(30))));
        // 자리가 없어 기다리기 시작한 뒤에 돌려준다
        await().atMost(Duration.ofSeconds(5)).until(() -> waiter.getState() == Thread.State.TIMED_WAITING);

        held.getFirst().close();

        waiter.join(Duration.ofSeconds(5));
        assertThat(waited.get()).isPresent();
    }

    /** 장애가 없었으면 빨리 늘린다 — 시작 2 에서 상한 80 까지 창 여섯 개(약 1분). */
    @Test
    void 첫_감소_전까지는_두_배씩_늘고_상한을_넘지_않는다() {
        fill();
        window(FAST, 20, 0);
        assertThat(limiter.limit()).as("3 × 2").isEqualTo(6);

        fill();
        window(FAST, 20, 0);
        assertThat(limiter.limit()).as("상한").isEqualTo(6);
    }

    /** 한 번 줄였으면 그 자리가 mock 이 견딜 만큼에 가깝다 — 그 뒤로는 조금씩 늘린다. */
    @Test
    void 줄인_뒤에는_창마다_하나씩_는다() {
        fill();
        window(FAST, 20, 0);
        window(Settings.SLOW_CALL.plusMillis(10), 20, 0);
        assertThat(limiter.limit()).as("6 의 절반").isEqualTo(3);

        for (int expected = 4; expected <= 6; expected++) {
            fill();
            window(FAST, 20, 0);
            assertThat(limiter.limit()).isEqualTo(expected);
        }
    }

    /** 등록만 오면 CANCEL 전용 자리는 비어 있다. 그래도 등록 자리를 다 썼으면 늘린다. */
    @Test
    void 등록만으로_등록_자리를_다_써도_늘린다() {
        acquire(JobType.REGISTER, 2);

        window(FAST, 20, 0);

        assertThat(limiter.limit()).isEqualTo(6);
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
        assertThat(failing.limit()).isEqualTo(6);
        record(failing, FAST, 15, 5);
        clock.advance(WINDOW);
        failing.record(FAST, false);
        assertThat(failing.limit()).as("21건 중 5건 실패(23%)").isEqualTo(3);
    }

    /** 한산할 때 한두 건의 실패 · 느림으로 비율이 크게 흔들린다. 그것만으로는 줄이지 않는다. */
    @Test
    void 창의_호출이_적으면_느리거나_실패해도_줄이지_않는다() {
        window(Settings.SLOW_CALL.plusMillis(10), 3, 1);
        assertThat(limiter.limit()).as("4건 — 최소 10건 미만").isEqualTo(3);

        window(Settings.SLOW_CALL.plusMillis(10), 9, 0);
        assertThat(limiter.limit()).as("10건이면 줄인다").isEqualTo(2);
    }

    /** 느리면 창 안에 끝나는 호출 자체가 준다. 자리를 다 쓴 채 느리면 호출이 적어도 줄인다 — 실패율은 그대로 10건을 본다. */
    @Test
    void 자리를_다_쓴_채_느리면_호출이_적어도_줄이고_실패는_10건을_기다린다() {
        fill();
        window(FAST, 3, 1);
        assertThat(limiter.limit()).as("자리를 다 썼어도 4건 중 1건 실패로는 줄이지 않는다").isEqualTo(3);

        fill();
        window(Settings.SLOW_CALL.plusMillis(10), 4, 0);
        assertThat(limiter.limit()).as("5건이지만 자리를 다 쓴 채 느리다").isEqualTo(2);
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
                Duration.ofMillis(100), 40, WINDOW, Duration.ofSeconds(1), 5, 20, 10, 3, 0.8), Settings.SLOW_CALL, clock);

        fill(tuned);
        record(tuned, FAST, 20, 0);
        clock.advance(WINDOW);
        tuned.record(FAST, false);
        assertThat(tuned.limit()).as("두 배, 상한 20").isEqualTo(20);

        record(tuned, Settings.SLOW_CALL, 20, 0);
        clock.advance(WINDOW);
        tuned.record(Settings.SLOW_CALL, false);
        assertThat(tuned.limit()).as("× 0.8").isEqualTo(16);

        fill(tuned);
        record(tuned, FAST, 20, 0);
        clock.advance(WINDOW);
        tuned.record(FAST, false);
        assertThat(tuned.limit()).as("줄인 뒤 +3").isEqualTo(19);
    }

    /** 장애가 지나면 빨리 돌아오되, 장애 직전의 절반까지만 두 배로 늘린다 — 다시 몰려 또 열리지 않게. */
    @Test
    void 차단기가_열리면_최소로_줄이고_직전의_절반까지는_두_배씩_돌아온다() {
        fill();
        window(FAST, 20, 0);
        assertThat(limiter.limit()).isEqualTo(6);

        limiter.shrinkToMin();
        assertThat(limiter.limit()).isEqualTo(2);
        limiter.shrinkToMin();

        fill();
        window(FAST, 20, 0);
        assertThat(limiter.limit()).as("6 의 절반까지 — 최소에서 다시 열려도 기억한다").isEqualTo(3);
        fill();
        window(FAST, 20, 0);
        assertThat(limiter.limit()).as("그 뒤로는 +1").isEqualTo(4);
    }

    /** 차단기가 닫힌 직후의 시험 호출은 느릴 수 있다. 최소라 더 줄일 수 없으면 기준점도 그대로 두어 빠른 회복을 지킨다. */
    @Test
    void 최소에서_느린_호출이_와도_차단기_뒤의_기준점을_지우지_않는다() {
        ConcurrencyLimiter wide = new ConcurrencyLimiter(new WorkerProperties.Concurrency(20, 1, 10, 2, 1,
                Duration.ofMillis(100), 40, WINDOW, Duration.ofSeconds(1), 5, 20, 10, 1, 0.5), Settings.SLOW_CALL, clock);
        fill(wide);
        record(wide, FAST, 20, 0);
        clock.advance(WINDOW);
        wide.record(FAST, false);
        assertThat(wide.limit()).isEqualTo(20);
        wide.shrinkToMin();

        fill(wide);
        wide.record(Settings.SLOW_CALL.plusMillis(10), false);
        clock.advance(WINDOW);
        wide.record(Settings.SLOW_CALL.plusMillis(10), false);
        assertThat(wide.limit()).as("자리를 다 쓴 채 느리지만 이미 최소").isEqualTo(2);

        fill(wide);
        record(wide, FAST, 20, 0);
        clock.advance(WINDOW);
        wide.record(FAST, false);
        assertThat(wide.limit()).as("기준점 10 까지 두 배 — 기준점을 지웠다면 +1 로 3").isEqualTo(4);
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
}
