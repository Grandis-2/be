package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.outbox.AggregateType;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * 회차 일정 · 예상 방문자 수가 바뀌었다(인프라가 받아 오픈 전에 미리 늘린다). 대기열용 {@link PreorderCampaignChanged} 와 같은 때 ·
 * 같은 일정 번호로 나가 번호가 빠지지 않는다. 표준 큐라 순서가 뒤집히므로 받는 쪽은 scheduleVersion 이 더 큰 것만 반영한다.
 *
 * @param expectedVisitors 관리자가 넣은 예상 방문자 수. 넣지 않았으면 null
 */
public record PreorderCampaignScalingPlanned(UUID productId, long scheduleVersion, Instant opensAt, Instant closesAt,
                                             boolean visible, Integer expectedVisitors, Instant changedAt,
                                             CampaignChange change) implements OutboxMessage {

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.PREORDER_CAMPAIGN_SCALING_PLANNED;
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
