package com.grandis.nova.worker.sync;

import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.job.Actor;
import com.grandis.nova.worker.job.AttemptResult;
import com.grandis.nova.worker.job.JobProgress;
import com.grandis.nova.worker.job.JobType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 다음 차례를 언제 · 몇 번째로 잡는가. 난수는 고정해 지연을 정확히 본다(0.5 면 흩지 않은 값). */
class RetryScheduleTest {

    static final WorkerProperties.Retry SETTINGS = new WorkerProperties.Retry(1, Duration.ofMillis(200),
            Duration.ofMillis(500), List.of(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(40)), 4, 3,
            Duration.ofMinutes(15), 0.2);

    final RetrySchedule schedule = new RetrySchedule(SETTINGS, () -> 0.5);

    @Test
    void 세대_안에서는_차례를_올리고_지연을_늘려_간다() {
        assertThat(next(JobType.REGISTER, 1, 1)).contains(new RetrySchedule.Next(
                new JobProgress(2, 2, 1, AttemptResult.TRANSIENT_FAILURE, Actor.ADMIN), Duration.ofSeconds(10)));
        assertThat(next(JobType.REGISTER, 2, 1).orElseThrow().delay()).isEqualTo(Duration.ofSeconds(20));
        assertThat(next(JobType.REGISTER, 3, 1).orElseThrow().delay()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void 세대의_차례를_다_쓰면_길게_쉬고_다음_세대의_첫_차례로_간다() {
        assertThat(next(JobType.REGISTER, 4, 1)).contains(new RetrySchedule.Next(
                new JobProgress(2, 1, 2, AttemptResult.TRANSIENT_FAILURE, Actor.ADMIN), Duration.ofMinutes(15)));
    }

    @Test
    void 등록은_세대를_다_쓰면_포기하고_취소는_포기하지_않는다() {
        assertThat(next(JobType.REGISTER, 4, 3)).isEmpty();
        assertThat(next(JobType.CANCEL, 4, 3).orElseThrow().progress())
                .isEqualTo(new JobProgress(2, 1, 4, AttemptResult.TRANSIENT_FAILURE, Actor.ADMIN));
        assertThat(next(JobType.CANCEL, 4, 30)).isPresent();
    }

    /** 실행 회차 · 시작한 쪽은 worker 가 바꾸지 않는다. 결과는 다음 차례가 조회부터 할지를 정한다. */
    @Test
    void 실행_회차와_시작한_쪽은_그대로_잇고_이번_결과를_싣는다() {
        JobProgress next = schedule.next(JobType.REGISTER, new JobProgress(7, 1, 1, null, Actor.SYSTEM),
                AttemptResult.UNKNOWN, null).orElseThrow().progress();

        assertThat(next).isEqualTo(new JobProgress(7, 2, 1, AttemptResult.UNKNOWN, Actor.SYSTEM));
    }

    @Test
    void 외부가_더_늦게_보내라고_하면_그_뒤로_흩어_기다리되_SQS_상한을_넘지_않는다() {
        JobProgress first = new JobProgress(1, 1, 1, null, Actor.SYSTEM);

        assertThat(schedule.next(JobType.REGISTER, first, AttemptResult.TRANSIENT_FAILURE, Duration.ofSeconds(30))
                .orElseThrow().delay()).isEqualTo(Duration.ofSeconds(33));
        assertThat(new RetrySchedule(SETTINGS, () -> 0.0).next(JobType.REGISTER, first,
                AttemptResult.TRANSIENT_FAILURE, Duration.ofSeconds(30)).orElseThrow().delay())
                .as("외부가 정한 시각보다 앞서지 않는다").isEqualTo(Duration.ofSeconds(30));
        assertThat(schedule.next(JobType.REGISTER, first, AttemptResult.TRANSIENT_FAILURE, Duration.ofSeconds(3))
                .orElseThrow().delay()).as("우리 지연이 더 길면 그대로").isEqualTo(Duration.ofSeconds(10));
        assertThat(schedule.next(JobType.REGISTER, first, AttemptResult.TRANSIENT_FAILURE, Duration.ofHours(1))
                .orElseThrow().delay()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void 지연은_설정한_폭_안에서_흩고_상한을_넘지_않는다() {
        RetrySchedule earliest = new RetrySchedule(SETTINGS, () -> 0.0);
        RetrySchedule latest = new RetrySchedule(SETTINGS, () -> 0.999999);

        assertThat(earliest.spread(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(8));
        assertThat(latest.spread(Duration.ofSeconds(10))).isBetween(Duration.ofMillis(11_990), Duration.ofSeconds(12));
        assertThat(latest.spread(Duration.ofMinutes(15))).isEqualTo(Duration.ofMinutes(15));
        assertThat(earliest.past(Duration.ofSeconds(10))).as("풀리는 시각보다 앞서지 않는다").isEqualTo(Duration.ofSeconds(10));
        assertThat(latest.past(Duration.ofSeconds(10))).isBetween(Duration.ofMillis(11_990), Duration.ofSeconds(12));
        assertThat(earliest.inRoundDelay()).isEqualTo(Duration.ofMillis(200));
        assertThat(latest.inRoundDelay()).isBetween(Duration.ofMillis(499), Duration.ofMillis(500));
    }

    private Optional<RetrySchedule.Next> next(JobType type, int attempt, int generation) {
        return schedule.next(type, new JobProgress(2, attempt, generation, null, Actor.ADMIN),
                AttemptResult.TRANSIENT_FAILURE, null);
    }
}
