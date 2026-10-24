package com.grandis.nova.payment.deadletter;

import com.grandis.nova.payment.deadletter.domain.DeadLetterStatus;
import com.grandis.nova.payment.deadletter.domain.DeadLetterWaitingQuery;
import com.grandis.nova.payment.deadletter.domain.DeadLetterWaitingQuery.WaitingGroup;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 되돌리기를 기다리는(OPEN · 보내다 멈춘 REDRIVING) DLQ 메시지 현황(모듈 공개 API). 지표가 읽는다.
 * 트랜잭션을 열지 않는다 — 전체 현황은 문장 하나다.
 */
@Service
public class DeadLetterBacklog {

    private final DeadLetterWaitingQuery waitingQuery;
    private final Clock clock;

    DeadLetterBacklog(DeadLetterWaitingQuery waitingQuery, Clock clock) {
        this.waitingQuery = waitingQuery;
        this.clock = clock;
    }

    /** 전체 합계 · 가장 오래된 것과 종류 · 사유별 수를 한 문장으로. */
    public DeadLetterBreakdown breakdown() {
        long total = 0;
        Instant oldestAt = null;
        Map<DeadLetterBreakdown.Kind, Long> byKind = new HashMap<>();
        for (WaitingGroup group : waitingQuery.countByKind(clock.instant().minus(DeadLetterStatus.STALE_REDRIVE))) {
            total += group.count();
            if (oldestAt == null || group.oldestAt().isBefore(oldestAt)) {
                oldestAt = group.oldestAt();
            }
            byKind.put(new DeadLetterBreakdown.Kind(group.eventKind(), group.failureReason().name()), group.count());
        }
        return new DeadLetterBreakdown(new DeadLetterWaiting(total, oldestAt), byKind);
    }
}
