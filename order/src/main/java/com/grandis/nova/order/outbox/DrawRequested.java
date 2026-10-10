package com.grandis.nova.order.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;
import java.util.UUID;

/**
 * 회차의 추첨을 요청한다(contracts/draw-entry-sync.md). worker 가 받아 Mock 이 받은 응모 수를 paidEntries 와 대조하고, 같으면 Mock 에
 * winnerCount 명 추첨을 맡긴다. 결과는 DRAW_RESULT_READY 로 돌아온다. 결제 완료 응모가 당첨 인원 이하면 보내지 않는다(전원 당첨).
 *
 * @param drawId      회차 id. 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param paidEntries 그 회차의 결제 완료 응모 수 — Mock 이 받은 수와 같아야 추첨한다
 * @param winnerCount 당첨 인원(paidEntries 보다 작다)
 */
public record DrawRequested(@JsonIgnore UUID drawId, long paidEntries, int winnerCount) implements OutboxMessage {

    public DrawRequested {
        Objects.requireNonNull(drawId, "drawId");
        if (winnerCount < 1 || paidEntries <= winnerCount) {
            throw new IllegalArgumentException("결제 완료 응모가 당첨 인원보다 많을 때만 추첨한다: paid=" + paidEntries + ", winners=" + winnerCount);
        }
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.DRAW_REQUESTED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.DRAW_CAMPAIGN;
    }

    @Override
    public UUID aggregateId() {
        return drawId;
    }
}
