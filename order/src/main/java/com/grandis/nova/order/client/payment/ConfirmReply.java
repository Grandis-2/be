package com.grandis.nova.order.client.payment;

/**
 * payment ConfirmResponse 와 같은 모양.
 *
 * @param result        APPROVED · DECLINED · PENDING(처리 중 · 결과 불명)
 * @param declineReason DECLINED 일 때만
 */
public record ConfirmReply(Result result, DeclineReason declineReason) {

    public enum Result {
        APPROVED,
        DECLINED,
        PENDING
    }
}
