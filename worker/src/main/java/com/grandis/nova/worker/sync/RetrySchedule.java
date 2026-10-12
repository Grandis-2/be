package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.DelayedResend;
import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.job.AttemptResult;
import com.grandis.nova.worker.job.JobProgress;
import com.grandis.nova.worker.job.JobType;

import java.time.Duration;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * 실패한 차례 다음에 언제 · 몇 번째로 다시 할지 정한다. 한 세대 안에서는 지연을 늘려 가고, 세대를 다 쓰면 길게 쉰 뒤
 * 다음 세대를 연다. REGISTER 는 세대를 다 쓰면 포기하고 CANCEL 은 포기하지 않는다.
 */
final class RetrySchedule {

    /** @param progress 다음 차례의 진행 속성 */
    record Next(JobProgress progress, Duration delay) {
    }

    private final WorkerProperties.Retry settings;
    private final DoubleSupplier random;

    /** @param random 0 이상 1 미만의 난수. 지연을 흩는 데 쓴다 */
    RetrySchedule(WorkerProperties.Retry settings, DoubleSupplier random) {
        this.settings = settings;
        this.random = random;
    }

    /**
     * @param outcome    이번 차례의 결과. 다음 차례가 조회부터 할지를 정한다
     * @param retryAfter 외부가 이만큼 뒤에 다시 보내라고 했다. 없으면 null
     * @return 다음 차례. 더 시도하지 않으면 비어 있다
     */
    Optional<Next> next(JobType type, JobProgress progress, AttemptResult outcome, Duration retryAfter) {
        if (progress.attempt() < settings.roundsPerGeneration()) {
            Duration backoff = settings.backoff().get(progress.attempt() - 1);
            return Optional.of(new Next(new JobProgress(progress.run(), progress.attempt() + 1, progress.generation(),
                    outcome, progress.actor()), atLeast(spread(backoff), retryAfter)));
        }
        if (type == JobType.REGISTER && progress.generation() >= settings.generations()) {
            return Optional.empty();
        }
        return Optional.of(new Next(new JobProgress(progress.run(), 1, progress.generation() + 1, outcome,
                progress.actor()), atLeast(spread(settings.generationGap()), retryAfter)));
    }

    /** 한 차례 안에서 곧바로 다시 보내기 전에 쉬는 시간. */
    Duration inRoundDelay() {
        long min = settings.inRoundDelayMin().toMillis();
        long max = settings.inRoundDelayMax().toMillis();
        return Duration.ofMillis(min + (long) ((max - min) * random.getAsDouble()));
    }

    /** base 보다 조금 뒤로(0 ~ +jitterRatio) 흩는다. 그때까지는 부를 수 없을 때 쓴다 — 풀리는 순간 한꺼번에 몰리지 않게. */
    Duration past(Duration base) {
        return capped(Duration.ofMillis((long) (base.toMillis() * (1 + settings.jitterRatio() * random.getAsDouble()))));
    }

    /** base 를 ±jitterRatio 만큼 흩는다. SQS 지연 상한을 넘지 않는다. */
    Duration spread(Duration base) {
        double factor = 1 + settings.jitterRatio() * (2 * random.getAsDouble() - 1);
        return capped(Duration.ofMillis((long) (base.toMillis() * factor)));
    }

    /** 외부가 정한 시각(floor)이 더 늦으면 그 뒤로 흩는다 — 같은 값을 받은 메시지들이 한 순간에 돌아오지 않게. */
    private Duration atLeast(Duration delay, Duration floor) {
        return floor == null || floor.compareTo(delay) <= 0 ? delay : past(floor);
    }

    private static Duration capped(Duration delay) {
        return delay.compareTo(DelayedResend.MAX_DELAY) > 0 ? DelayedResend.MAX_DELAY : delay;
    }
}
