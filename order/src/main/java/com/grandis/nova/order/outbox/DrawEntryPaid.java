package com.grandis.nova.order.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;
import java.util.UUID;

/**
 * 응모비를 결제한 응모(contracts/draw-entry-sync.md). worker 가 받아 이 본문 그대로 Mock(추첨)에 응모를 넘긴다 — 재시도해도 본문이 같고,
 * 멱등 키는 응모 id 라 Mock 이 같은 응모로 본다. 전송 작업 표는 두지 않는다: 재시도 · 실패 보관은 SQS(가시성 지연 · DLQ)가 한다.
 * 배송지 · 결제 정보는 싣지 않는다.
 *
 * @param entryId    응모 id. Mock 의 멱등 키. 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param drawId     회차 id
 * @param customerId 응모한 회원 id
 */
public record DrawEntryPaid(@JsonIgnore UUID entryId, UUID drawId, UUID customerId) implements OutboxMessage {

    public DrawEntryPaid {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(drawId, "drawId");
        Objects.requireNonNull(customerId, "customerId");
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.DRAW_ENTRY_PAID;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.DRAW_ENTRY;
    }

    @Override
    public UUID aggregateId() {
        return entryId;
    }
}
