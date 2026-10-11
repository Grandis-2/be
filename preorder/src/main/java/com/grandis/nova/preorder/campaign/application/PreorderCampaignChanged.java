package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.outbox.AggregateType;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * 회차 일정이 바뀌었다(대기열이 받는다). 대기열은 원장을 읽을 수 없어 payload 의 일정을 그대로 믿는다 —
 * 회차 행을 잠근 채 확정한 값이다. 표준 큐라 순서가 뒤집히므로 받는 쪽은 scheduleVersion 이 더 큰 것만 반영한다.
 *
 * @param visible 상품 공개 여부. 비공개면 대기열은 그 회차의 줄을 열지 않는다
 */
public record PreorderCampaignChanged(UUID productId, long scheduleVersion, Instant opensAt, Instant closesAt,
                                      boolean visible, Instant changedAt, CampaignChange change)
        implements OutboxMessage {

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.PREORDER_CAMPAIGN_CHANGED;
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
