package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 우리 요청이 잘못돼 거절이 이어지거나 mock 이 아닌 곳에 닿고 있으면 받기를 잠시 멈춘다. 그대로 두면 큐의 작업이 모두 같은 이유로
 * 최종 실패가 된다. 멈춘 시간이 지나면 다시 받아 보고, 또 이어지면 다시 멈춘다.
 */
public final class RejectGuard {

    private static final Logger log = LoggerFactory.getLogger(RejectGuard.class);

    private final JobType type;
    private final WorkerProperties.RejectGuard settings;
    private final Clock clock;
    private final Deque<Instant> recent = new ArrayDeque<>();
    private Instant blockedUntil = Instant.EPOCH;

    RejectGuard(JobType type, WorkerProperties.RejectGuard settings, Clock clock) {
        this.type = type;
        this.settings = settings;
        this.clock = clock;
    }

    /** 잘못된 요청으로 거절됐거나 설정 오류로 보이는 응답을 받았다. */
    synchronized void record(String reason) {
        Instant now = clock.instant();
        if (now.isBefore(blockedUntil)) {
            // 멈추기 전에 받아 둔 건의 거절이다. 세어 두면 다시 받자마자 또 멈춘다
            return;
        }
        recent.addLast(now);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(settings.per()))) {
            recent.removeFirst();
        }
        if (recent.size() >= settings.invalidRequests()) {
            blockedUntil = now.plus(settings.pause());
            recent.clear();
            log.error("외부 mock 이 요청을 연달아 거절한다 — 사람 확인 필요. {} 동안 이 종류의 받기를 멈춘다 "
                    + "jobType={} reason={} count={}", settings.pause(), type, reason, settings.invalidRequests());
        }
    }

    public boolean blocked() {
        return !remaining().isZero();
    }

    /** 받기를 멈춘 남은 시간. 멈추지 않았으면 0. */
    public synchronized Duration remaining() {
        Duration remaining = Duration.between(clock.instant(), blockedUntil);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
