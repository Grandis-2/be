package com.grandis.nova.worker.sync;

import com.grandis.nova.worker.job.AttemptResult;
import com.grandis.nova.worker.job.JobProgress;
import com.grandis.nova.worker.job.JobReady;
import com.grandis.nova.worker.mock.MockGateway;
import com.grandis.nova.worker.mock.MockResult;
import com.grandis.nova.worker.mock.Verdict;

import java.time.Instant;

/**
 * 외부 등록의 한 차례. 멱등 키는 예약의 공개 id 이고 본문은 메시지에 실려 온 그대로다 — 몇 번을 보내도 외부는 한 번만 등록한다.
 * 직전 차례의 결과를 모르면 같은 키로 조회부터 한다. 저장되지 않은 것이 확실한 실패만 곧바로 다시 보낸다.
 */
final class RegisterTurn implements Turn {

    private final MockGateway mock;
    private final InRoundRetry inRound;

    RegisterTurn(MockGateway mock, InRoundRetry inRound) {
        this.mock = mock;
        this.inRound = inRound;
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
        MockResult first = mock.register(key, job.request());
        return Outcome.of(inRound.from(first, () -> mock.register(key, job.request())),
                startedAt == null ? first.startedAt() : startedAt);
    }
}
