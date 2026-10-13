package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.preorder.deadletter.domain.DeadLetterEventRepository;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 되돌리기를 기다리는(OPEN · 보내다 멈춘 REDRIVING) DLQ 메시지 현황(모듈 공개 API). 지표와 관리자 운영 현황이 읽는다. */
@Service
@Transactional(readOnly = true)
public class DeadLetterBacklog {

    private final DeadLetterEventRepository events;
    private final Clock clock;

    DeadLetterBacklog(DeadLetterEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public long waitingCount() {
        return events.countWaiting(clock.instant().minus(DeadLetterStatus.STALE_REDRIVE));
    }

    /** 가장 오래 방치된 행의 나이. 없으면 0. */
    public Duration oldestWaitingAge() {
        return events.findOldestWaitingCreatedAt(clock.instant().minus(DeadLetterStatus.STALE_REDRIVE))
                .map(createdAt -> Duration.between(createdAt, clock.instant()))
                .filter(age -> !age.isNegative())
                .orElse(Duration.ZERO);
    }

    /** 기다리는 수와 가장 오래된 것. productId 를 주면 그 상품의 예약에 묶인 것만, null 이면 전체를 본다. */
    public DeadLetterWaiting waiting(UUID productId) {
        Instant staleBefore = clock.instant().minus(DeadLetterStatus.STALE_REDRIVE);
        if (productId == null) {
            return new DeadLetterWaiting(events.countWaiting(staleBefore),
                    events.findOldestWaitingCreatedAt(staleBefore).orElse(null));
        }
        return new DeadLetterWaiting(events.countWaitingOfProduct(productId, staleBefore),
                events.findOldestWaitingCreatedAtOfProduct(productId, staleBefore).orElse(null));
    }
}
