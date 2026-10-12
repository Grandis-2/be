package com.grandis.nova.worker.sync;

import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.job.AttemptResult;
import com.grandis.nova.worker.job.JobProgress;
import com.grandis.nova.worker.job.JobReady;
import com.grandis.nova.worker.mock.MockGateway;
import com.grandis.nova.worker.mock.MockResult;
import com.grandis.nova.worker.mock.Verdict;

import java.time.Duration;
import java.time.Instant;

/**
 * 외부 등록의 한 차례. 멱등 키는 예약의 공개 id 이고 본문은 메시지에 실려 온 그대로다 — 몇 번을 보내도 외부는 한 번만 등록한다.
 * 직전 차례의 결과를 모르면 같은 키로 조회부터 한다. 저장되지 않은 것이 확실한 실패만 곧바로 다시 보낸다.
 */
final class RegisterTurn implements Turn {

    private final MockGateway mock;
    private final RetrySchedule schedule;
    private final int inRound;

    RegisterTurn(MockGateway mock, RetrySchedule schedule, WorkerProperties.Retry settings) {
        this.mock = mock;
        this.schedule = schedule;
        this.inRound = settings.inRound();
    }

    @Override
    public Outcome run(JobReady job, JobProgress progress) {
        String key = job.preorderId();
        Instant startedAt = null;
        if (progress.lastOutcome() == AttemptResult.UNKNOWN) {
            MockResult found = mock.lookup(key);
            startedAt = found.startedAt();
            if (found.verdict() == Verdict.TRANSIENT || found.verdict() == Verdict.UNKNOWN) {
                // 조회도 실패했다 — 등록됐는지 여전히 모르므로 다음 차례도 조회부터 한다
                return new Outcome(found, AttemptResult.UNKNOWN, startedAt);
            }
            if (found.verdict() != Verdict.ABSENT) {
                return Outcome.of(found, startedAt);
            }
        }
        MockResult result = mock.register(key, job.request());
        if (startedAt == null) {
            startedAt = result.startedAt();
        }
        for (int retried = 0; retried < inRound && result.retryNow(); retried++) {
            if (!pause(schedule.inRoundDelay())) {
                break;
            }
            MockResult again = mock.register(key, job.request());
            if (again.verdict() == Verdict.NOT_CALLED) {
                // 방금 실패로 차단기가 열렸다. 이 차례는 이미 한 번 불렀으므로 그 실패로 끝낸다
                break;
            }
            result = again;
        }
        return Outcome.of(result, startedAt);
    }

    /** @return 끝까지 쉬었으면 true. 종료로 깨면 false — 더 부르지 않고 지금 결과로 차례를 끝낸다 */
    private static boolean pause(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
