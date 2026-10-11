package com.grandis.nova.order.draw.api;

import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.draw.ConfirmedEntryPayment;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;

/**
 * 응모비 승인 결과. 프론트는 result 로 가른다 — 주문 결제의 같은 응답과 규칙이 같다.
 * - APPROVED: 결제 완료(entryStatus = PAID). 추첨 대상이다
 * - DECLINED: declineReason 문구를 보이고 결제를 준비부터 다시 한다
 * - PENDING: 결제 확인 중(AUTHORIZING). 내 응모 조회(GET)로 폴링한다. 오래 머물면 같은 tossOrderId · paymentKey 로 승인을 한 번만 다시 보낸다
 * 오류 봉투 중 503 · 500 은 응모가 확인 중일 수 있다는 뜻이다 — 준비를 다시 부르지 말고 같은 승인을 한 번 다시 보낸 뒤 폴링한다.
 *
 * @param declineReason DECLINED 일 때만
 */
public record EntryConfirmResponse(ConfirmedEntryPayment.Result result, DrawEntryStatus entryStatus, DeclineReason declineReason) {

    static EntryConfirmResponse of(ConfirmedEntryPayment confirmed) {
        return new EntryConfirmResponse(confirmed.result(), confirmed.entryStatus(), confirmed.declineReason());
    }
}
