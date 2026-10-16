package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.campaign.domain.OpenAlertDispatchStore;
import com.grandis.nova.preorder.campaign.domain.OpenAlertDispatchStore.Backlog;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/** 끝나지 않은 오픈 알림 발송 현황(모듈 공개 API). 지표가 읽는다. */
@Service
public class OpenAlertBacklog {

    private final OpenAlertDispatchStore dispatches;
    private final Clock clock;

    OpenAlertBacklog(OpenAlertDispatchStore dispatches, Clock clock) {
        this.dispatches = dispatches;
        this.clock = clock;
    }

    public Pending pending() {
        Backlog backlog = dispatches.backlog();
        Duration oldestAge = backlog.oldestCreatedAt() == null ? Duration.ZERO
                : Duration.between(backlog.oldestCreatedAt(), clock.instant());
        return new Pending(backlog.count(), oldestAge.isNegative() ? Duration.ZERO : oldestAge);
    }

    /** @param oldestAge 가장 오래된 발송이 시작된 뒤 지난 시간. 없으면 0 */
    public record Pending(long count, Duration oldestAge) {
    }
}
