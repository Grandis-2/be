package com.grandis.nova.worker.sync;

import com.grandis.nova.worker.job.AttemptResult;
import com.grandis.nova.worker.job.JobProgress;
import com.grandis.nova.worker.job.JobReady;
import com.grandis.nova.worker.mock.MockResult;

import java.time.Instant;

/** 작업 하나의 한 차례. 자리 하나를 쥔 채 외부를 순서대로 부르고(조회 → 호출 → 즉시 재시도) 마지막 결과를 돌려준다. */
@FunctionalInterface
interface Turn {

    Outcome run(JobReady job, JobProgress progress);

    /**
     * @param last      마지막 호출의 결과. 차례를 어떻게 끝낼지는 이 판정으로 정한다
     * @param result    시도 기록에 남길 결과. 조회가 실패해 등록 여부를 여전히 모르면 UNKNOWN 이다. 시도로 세지 않으면 null
     * @param startedAt 이 차례의 첫 호출을 시작한 시각
     */
    record Outcome(MockResult last, AttemptResult result, Instant startedAt) {

        static Outcome of(MockResult last, Instant startedAt) {
            return new Outcome(last, last.attemptResult(), startedAt);
        }
    }
}
