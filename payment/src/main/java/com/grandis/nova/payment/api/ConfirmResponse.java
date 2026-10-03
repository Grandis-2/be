package com.grandis.nova.payment.api;

import com.grandis.nova.payment.confirm.ConfirmResult;
import com.grandis.nova.payment.domain.enums.DeclineReason;

/**
 * 승인 결과. 결제사 원본 코드 · 문구는 싣지 않는다.
 *
 * @param result        APPROVED · DECLINED · PENDING(처리 중 · 결과 불명 — 확정되면 결과 이벤트가 간다)
 * @param declineReason DECLINED 일 때만
 */
public record ConfirmResponse(ConfirmResult.Result result, DeclineReason declineReason) {

    static ConfirmResponse from(ConfirmResult result) {
        return new ConfirmResponse(result.result(), result.declineReason());
    }
}
