package com.grandis.nova.order.draw.api;

import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawEntryResult;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 내 응모. 본인에게만 보인다.
 *
 * @param status AWAITING_PAYMENT(응모비 결제 전 — 응모 기간 안에 결제해야 추첨 대상) · AUTHORIZING(결제 확인 중) · PAID(추첨 대상)
 * @param result 추첨 결과 WON · LOST. 결과 반영 전이거나 결제 완료(PAID)가 아니면 null — 당첨이면 내 주문 목록에 0원 주문(source DRAW)이 있다
 * @param draw   그 회차(회차 상세와 같은 모양) — 화면이 회차를 다시 조회하지 않게 같이 준다
 */
public record DrawEntryResponse(UUID entryId, UUID drawId, DrawEntryStatus status, DrawEntryResult result, ShipTo shipTo,
                                Instant createdAt, DrawResponse draw) {

    /** 응모 직후(결과 전). */
    static DrawEntryResponse of(DrawEntry entry, DrawResponse draw) {
        return of(entry, null, draw);
    }

    static DrawEntryResponse of(DrawEntry entry, DrawEntryResult result, DrawResponse draw) {
        var to = entry.shipTo();
        return new DrawEntryResponse(entry.id(), entry.campaignId(), entry.status(), result,
                new ShipTo(to.name(), to.phone(), to.postalCode(), to.line1(), to.line2()), entry.createdAt(), draw);
    }

    /** 응모 때 받은 배송지(본인에게만). 개인정보라 toString 에 값을 싣지 않는다. */
    public record ShipTo(String name, String phone, String postalCode, String line1, String line2) {

        @Override
        public String toString() {
            return "ShipTo[***]";
        }
    }
}
