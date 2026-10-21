package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.preorder.deadletter.domain.DeadLetterEventRepository;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterEventRepository.WaitingGroup;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus;
import com.grandis.nova.preorder.event.InboundEventType;
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

    static final String OTHER = "OTHER";
    static final String UNKNOWN = "UNKNOWN";

    private final DeadLetterEventRepository events;
    private final Clock clock;

    DeadLetterBacklog(DeadLetterEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /** 전체 합계 · 가장 오래된 것과 종류 · 사유별 수를 한 문장으로. */
    public DeadLetterSummary summary() {
        long total = 0;
        Instant oldestAt = null;
        Map<DeadLetterSummary.Kind, Long> byKind = new HashMap<>();
        for (WaitingGroup group : events.countWaitingByKind(staleBefore())) {
            total += group.getCount();
            if (oldestAt == null || group.getOldestAt().isBefore(oldestAt)) {
                oldestAt = group.getOldestAt();
            }
            byKind.merge(new DeadLetterSummary.Kind(kindOf(group.getEventType()), group.getFailureReason().name()),
                    group.getCount(), Long::sum);
        }
        return new DeadLetterSummary(new DeadLetterWaiting(total, oldestAt), byKind);
    }

    /** 기다리는 수와 가장 오래된 것. productId 를 주면 그 상품의 예약에 묶인 것만, null 이면 전체를 본다. */
    public DeadLetterWaiting waiting(UUID productId) {
        if (productId == null) {
            return summary().total();
        }
        Instant staleBefore = staleBefore();
        return new DeadLetterWaiting(events.countWaitingOfProduct(productId, staleBefore),
                events.findOldestWaitingCreatedAtOfProduct(productId, staleBefore).orElse(null));
    }

    private Instant staleBefore() {
        return clock.instant().minus(DeadLetterStatus.STALE_REDRIVE);
    }

    private static String kindOf(String eventType) {
        if (eventType == null) {
            return UNKNOWN;
        }
        return InboundEventType.isKnown(eventType) ? eventType : OTHER;
    }
}
