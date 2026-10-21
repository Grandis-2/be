package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.preorder.deadletter.domain.DeadLetterEventRepository;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterEventRepository.WaitingTotal;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterWaitingQuery;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterWaitingQuery.WaitingGroup;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 되돌리기를 기다리는(OPEN · 보내다 멈춘 REDRIVING) DLQ 메시지 현황(모듈 공개 API). 지표와 관리자 운영 현황이 읽는다.
 * 트랜잭션을 열지 않는다 — 전체 현황은 문장 하나다.
 */
@Service
public class DeadLetterBacklog {

    private final DeadLetterWaitingQuery waitingQuery;
    private final DeadLetterEventRepository events;
    private final Clock clock;

    DeadLetterBacklog(DeadLetterWaitingQuery waitingQuery, DeadLetterEventRepository events, Clock clock) {
        this.waitingQuery = waitingQuery;
        this.events = events;
        this.clock = clock;
    }

    /** 전체 합계 · 가장 오래된 것과 종류 · 사유별 수를 한 문장으로. */
    public DeadLetterBreakdown breakdown() {
        long total = 0;
        Instant oldestAt = null;
        Map<DeadLetterBreakdown.Kind, Long> byKind = new HashMap<>();
        for (WaitingGroup group : waitingQuery.countByKind(staleBefore())) {
            total += group.count();
            if (oldestAt == null || group.oldestAt().isBefore(oldestAt)) {
                oldestAt = group.oldestAt();
            }
            byKind.put(new DeadLetterBreakdown.Kind(group.eventKind(), group.failureReason().name()), group.count());
        }
        return new DeadLetterBreakdown(new DeadLetterWaiting(total, oldestAt), byKind);
    }

    /** 기다리는 수와 가장 오래된 것(같은 문장의 짝). productId 를 주면 그 상품의 예약에 묶인 것만, null 이면 전체를 본다. */
    public DeadLetterWaiting waiting(UUID productId) {
        if (productId == null) {
            return breakdown().total();
        }
        WaitingTotal found = events.countWaitingOfProduct(productId, staleBefore());
        return new DeadLetterWaiting(found.getCount(), found.getOldestAt());
    }

    private Instant staleBefore() {
        return clock.instant().minus(DeadLetterStatus.STALE_REDRIVE);
    }
}
