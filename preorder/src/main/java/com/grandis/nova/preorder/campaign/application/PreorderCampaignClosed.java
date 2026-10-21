package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.outbox.AggregateType;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * 회차 접수가 마감됐다(catalog 가 받는다). 마감 시각이 지나거나 조기 마감 · 판매 중지로 닫힐 때 한 번, 다시 열었다 닫으면 또 한 번 나간다 —
 * 받는 쪽은 closesAt 이 더 늦은 것을 최신으로 본다. 건수는 회차 행을 공유 잠금으로 읽은 뒤 센 값이다(마감 직전 접수까지 들어간다).
 *
 * @param issuedCount 발급한 순번 수(취소된 예약 포함)
 * @param activeCount 지금 유효한 예약 수(취소 중 · 취소 완료 제외)
 */
public record PreorderCampaignClosed(UUID productId, Instant closesAt, long issuedCount, long activeCount)
        implements OutboxMessage {

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.PREORDER_CAMPAIGN_CLOSED;
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
