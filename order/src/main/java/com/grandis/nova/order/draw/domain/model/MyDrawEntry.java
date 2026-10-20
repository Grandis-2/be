package com.grandis.nova.order.draw.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 내 응모 목록의 한 줄 — 응모와 그 회차의 결과 반영 여부를 한 문장으로 읽은 것이다. 따로 읽으면 사이에 반영이 커밋될 때 당첨 주문이 없는 응모와
 * 반영 표식이 섞여 당첨자에게 낙첨이 보인다.
 *
 * @param wonOrderId 당첨 주문. 반영 전 · 낙첨이면 null
 * @param settled    그 회차의 결과를 반영했다
 */
public record MyDrawEntry(UUID entryId, UUID campaignId, DrawEntryStatus status, Instant createdAt, UUID wonOrderId, boolean settled) {

    public MyDrawEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * now 의 배지(2026-10-18 결정). 결제한 응모는 반영 전이면 결과 대기, 반영 뒤엔 당첨 주문이 있으면 당첨 · 없으면 낙첨이다 — 추첨이 정해진 뒤에
     * 결제 완료된 응모(추첨에 들지 않아 수동 환불 대상, DrawEntryPaymentResults)도 낙첨으로 보인다. 승인 중이면 마감 전후와 상관없이 결제 확인 중이다.
     * 결제 대기는 마감 전이면 결제 대기, 마감부터는 미결제 마감이다(마감 == now 는 마감 — {@link DrawCampaign#phaseAt}).
     */
    public MyDrawEntryState stateAt(Instant closesAt, Instant now) {
        if (status == DrawEntryStatus.PAID) {
            if (!settled) {
                return MyDrawEntryState.AWAITING_RESULT;
            }
            return wonOrderId != null ? MyDrawEntryState.WON : MyDrawEntryState.LOST;
        }
        if (status == DrawEntryStatus.AUTHORIZING) {
            return MyDrawEntryState.PAYMENT_CONFIRMING;
        }
        return now.isBefore(closesAt) ? MyDrawEntryState.AWAITING_PAYMENT : MyDrawEntryState.CLOSED_UNPAID;
    }
}
