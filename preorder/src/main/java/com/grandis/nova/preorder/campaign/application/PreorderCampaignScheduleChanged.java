package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.outbox.AggregateType;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * 회차 일정이 바뀌었다(catalog 가 받는다). 대기열용 {@link PreorderCampaignChanged} 와 같은 때 · 같은 값으로 나간다 — 일정 번호가
 * 오르는 변경은 모두 실리므로 번호가 빠지지 않는다. 표준 큐라 순서가 뒤집히므로 받는 쪽은 scheduleVersion 이 더 큰 것만 반영한다.
 */
public record PreorderCampaignScheduleChanged(UUID productId, long scheduleVersion, Instant opensAt, Instant closesAt,
                                              Instant changedAt, CampaignChange change) implements OutboxMessage {

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.PREORDER_CAMPAIGN_SCHEDULE_CHANGED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.PREORDER_CAMPAIGN;
    }

    @Override
    public UUID aggregateId() {
        return productId;
    }
}
