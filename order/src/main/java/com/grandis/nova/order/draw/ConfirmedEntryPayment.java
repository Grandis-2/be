package com.grandis.nova.order.draw;

import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;

import java.util.Objects;

/**
 * 응모비 승인 요청의 결과.
 *
 * @param entryStatus   반영 뒤 응모 상태
 * @param declineReason DECLINED 일 때만
 */
public record ConfirmedEntryPayment(Result result, DrawEntryStatus entryStatus, DeclineReason declineReason) {

    public ConfirmedEntryPayment {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(entryStatus, "entryStatus");
        if ((result == Result.DECLINED) != (declineReason != null)) {
            throw new IllegalArgumentException("거절일 때만 사유가 있다: result=" + result);
        }
    }

    static ConfirmedEntryPayment approved() {
        return new ConfirmedEntryPayment(Result.APPROVED, DrawEntryStatus.PAID, null);
    }

    static ConfirmedEntryPayment pending() {
        return new ConfirmedEntryPayment(Result.PENDING, DrawEntryStatus.AUTHORIZING, null);
    }

    public enum Result {
        APPROVED,
        DECLINED,
        /** 결제 확인 중(처리 중 · 결과 불명 · 응답을 못 받음). 같은 요청을 다시 보내거나 내 응모를 조회한다. */
        PENDING
    }
}
