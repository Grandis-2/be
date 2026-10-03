package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.redis.ControlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 일정을 하나도 모르면 전체 재발행을 요청한다. 연달아 비어 있으면(정말 회차가 없을 때) 간격을 5분에서 1시간까지 늘리고,
 * 일정을 알게 되면 처음 간격으로 되돌린다. 표식은 Redis 라 리더가 바뀌어도 이어진다. 배분 회차와 따로 돈다.
 */
@Component
class ScheduleResync {

    static final Duration FIRST_QUIET = Duration.ofMinutes(5);
    static final Duration MAX_QUIET = Duration.ofHours(1);
    static final String EMPTY_REASON = "SCHEDULE_EMPTY";
    private static final Logger log = LoggerFactory.getLogger(ScheduleResync.class);

    private final ControlStore store;
    private final ScheduleResyncRequester requester;
    private final ControlMetrics metrics;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    /** 이 노드가 모르는 동안 센 횟수가 Redis 에 남아 있을 수 있다. 일정을 알게 되면 한 번 지운다. */
    private final AtomicBoolean attemptsOutstanding = new AtomicBoolean(true);

    ScheduleResync(ControlStore store, ScheduleResyncRequester requester, ControlMetrics metrics) {
        this.store = store;
        this.requester = requester;
        this.metrics = metrics;
    }

    /** n 번째 연속 요청 뒤 기다릴 기간. 두 배씩, 상한까지. */
    static Duration quietAfter(long attempt) {
        long doublings = Math.clamp(attempt - 1, 0, 10);
        Duration quiet = FIRST_QUIET.multipliedBy(1L << doublings);
        return quiet.compareTo(MAX_QUIET) > 0 ? MAX_QUIET : quiet;
    }

    void requestIfQuiet() {
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        attemptsOutstanding.set(true);
        store.resyncRequestedRecently()
                .flatMap(recent -> recent ? Mono.<Long>empty() : store.nextResyncAttempt()
                        .flatMap(attempt -> requester.request(EMPTY_REASON)
                                .then(store.markResyncRequested(quietAfter(attempt)))
                                .thenReturn(attempt)))
                .doOnNext(attempt -> {
                    metrics.resyncRequested();
                    // 정말 회차가 없으면 계속 비어 있다 — 처음 한 번만 INFO 로 남긴다
                    if (attempt == 1) {
                        log.info("회차 일정을 하나도 몰라 전체 재발행을 요청했다");
                    } else {
                        log.debug("회차 일정이 여전히 비어 재발행을 다시 요청했다(연속 {}번째)", attempt);
                    }
                })
                .doFinally(signal -> inFlight.set(false))
                .subscribe(attempt -> {
                }, e -> log.warn("회차 일정 재발행을 요청하지 못했다 — 다음 회차에 다시 한다: {}", e.toString()));
    }

    /** 일정을 알게 됐다. 연속 횟수를 지워 다음에 비면 처음 간격부터 요청한다. */
    void known() {
        if (attemptsOutstanding.compareAndSet(true, false)) {
            store.clearResyncAttempts().subscribe(cleared -> {
            }, e -> attemptsOutstanding.set(true));
        }
    }
}
