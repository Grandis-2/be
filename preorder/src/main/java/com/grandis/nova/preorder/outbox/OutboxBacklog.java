package com.grandis.nova.preorder.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 아직 보내지 못한 preorder 발행분 현황(모듈 공개 API). 지표가 주기적으로 읽는다. */
@Service
@Transactional(readOnly = true)
public class OutboxBacklog {

    private static final List<String> OWN_EVENT_TYPES = OutboundEventType.names();

    private final OutboxEventRepository outboxEvents;

    OutboxBacklog(OutboxEventRepository outboxEvents) {
        this.outboxEvents = outboxEvents;
    }

    /** 미발행 행 수. 늘어나면 전송이 막힌 것이다. */
    public long unpublishedCount() {
        return outboxEvents.countUnpublished(OWN_EVENT_TYPES);
    }

    /** 미발행 행 가운데 가장 많이 실패한 횟수. 한 행만 계속 실패하는 것(독이 든 메시지)을 드러낸다. */
    public int maxUnpublishedAttempts() {
        return outboxEvents.maxUnpublishedAttempts(OWN_EVENT_TYPES);
    }
}
