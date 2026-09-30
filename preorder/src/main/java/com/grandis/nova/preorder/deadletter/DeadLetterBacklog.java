package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.preorder.deadletter.domain.DeadLetterEventRepository;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/** 되돌리기를 기다리는(OPEN · 보내다 멈춘 REDRIVING) DLQ 메시지 현황(모듈 공개 API). 지표가 주기적으로 읽는다. */
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
}
