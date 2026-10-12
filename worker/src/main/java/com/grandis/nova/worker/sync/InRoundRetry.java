package com.grandis.nova.worker.sync;

import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.mock.MockResult;
import com.grandis.nova.worker.mock.Verdict;

import java.time.Duration;
import java.util.function.Supplier;

/** 한 차례 안에서 곧바로 다시 보내기. 저장되지 않은 것이 확실한 일시 실패만, 잠깐 쉬고 정해진 횟수까지 같은 호출을 되풀이한다. */
final class InRoundRetry {

    private final RetrySchedule schedule;
    private final int times;
    private final ShutdownSignal shutdown;

    InRoundRetry(RetrySchedule schedule, WorkerProperties.Retry settings, ShutdownSignal shutdown) {
        this.schedule = schedule;
        this.times = settings.inRound();
        this.shutdown = shutdown;
    }

    /**
     * @param first 이 차례의 첫 호출 결과
     * @param call  같은 호출(같은 키 · 같은 본문)
     * @return 마지막으로 부른 결과
     */
    MockResult from(MockResult first, Supplier<MockResult> call) {
        MockResult result = first;
        for (int retried = 0; retried < times && result.retryNow(); retried++) {
            // 내려가는 중이면 더 부르지 않는다 — 지금 결과로 차례를 끝내고 다음 차례는 다른 worker 가 한다
            if (shutdown.stopping() || !pause(schedule.inRoundDelay())) {
                break;
            }
            MockResult again = call.get();
            if (again.verdict() == Verdict.NOT_CALLED) {
                // 방금 실패로 차단기가 열렸다. 이 차례는 이미 한 번 불렀으므로 그 실패로 끝낸다
                break;
            }
            result = again;
        }
        return result;
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
