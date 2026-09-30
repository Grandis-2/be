package com.grandis.nova.payment.client.toss;

/**
 * 결제 전액 취소 요청(POST /v1/payments/{paymentKey}/cancel). 부분 취소는 쓰지 않는다 — cancelAmount 를 싣지 않으면 전액이다.
 *
 * @param paymentKey   취소할 결제(경로). toString 에 싣지 않는다
 * @param cancelReason 필수, 최대 200자(토스 문서)
 */
public record TossCancelRequest(String paymentKey, String cancelReason) {

    public TossCancelRequest {
        TossRequestRules.requirePaymentKey(paymentKey);
        if (cancelReason == null || cancelReason.isBlank() || cancelReason.length() > TossRequestRules.CANCEL_REASON_MAX) {
            throw new IllegalArgumentException("취소 사유는 비어 있지 않은 " + TossRequestRules.CANCEL_REASON_MAX + "자 이하여야 한다");
        }
    }

    /** 토스로 보내는 본문. paymentKey 는 경로에 들어간다. */
    record Body(String cancelReason) {
    }

    Body body() {
        return new Body(cancelReason);
    }

    @Override
    public String toString() {
        return "TossCancelRequest[paymentKey=" + TossRequestRules.MASKED + ", cancelReason=" + cancelReason + "]";
    }
}
