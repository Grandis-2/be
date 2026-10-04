package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.outbox.AggregateType;
import com.grandis.nova.catalog.outbox.OutboundEventType;
import com.grandis.nova.catalog.outbox.OutboxMessage;

/**
 * 사전예약 오픈 뒤 판매 중지(회차 취소)를 접수했다 — preorder 가 회차를 지금 마감하고 진행 중 예약의 취소를 시작한다(preorder-events).
 * 계약: contracts/catalog-events.md.
 *
 * catalog 의 다른 이벤트와 달리 payload 에 상품 id 를 싣는다. preorder 가 이 이벤트를 먼저 정의하고 payload 의 productId 로 읽는다 —
 * 받는 쪽 모양에 맞춘다. 봉투의 aggregateId 도 같은 값이다.
 *
 * @param reason 관리자가 적은 취소 사유(500자 이하). preorder 가 예약 이력에 남긴다
 */
public record PreorderCampaignCanceled(Long productId, String reason) implements OutboxMessage {

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.PREORDER_CAMPAIGN_CANCELED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.PRODUCT;
    }

    @Override
    public Long aggregateId() {
        return productId;
    }
}
