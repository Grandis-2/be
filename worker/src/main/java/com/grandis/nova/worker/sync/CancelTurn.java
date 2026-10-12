package com.grandis.nova.worker.sync;

import com.grandis.nova.worker.job.JobProgress;
import com.grandis.nova.worker.job.JobReady;
import com.grandis.nova.worker.mock.MockGateway;
import com.grandis.nova.worker.mock.MockResult;

/**
 * 외부 취소의 한 차례. 본문은 메시지에 실려 온 그대로다. 취소는 여러 번 보내도 결과가 같아서, 직전 차례의 결과를 몰라도
 * 조회하지 않고 다시 보낸다.
 */
final class CancelTurn implements Turn {

    private final MockGateway mock;
    private final InRoundRetry inRound;

    CancelTurn(MockGateway mock, InRoundRetry inRound) {
        this.mock = mock;
        this.inRound = inRound;
    }

    @Override
    public Outcome run(JobReady job, JobProgress progress) {
        MockResult first = mock.cancel(job.request());
        return Outcome.of(inRound.from(first, () -> mock.cancel(job.request())), first.startedAt());
    }
}
