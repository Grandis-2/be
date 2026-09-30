package com.grandis.nova.payment.client.toss;

/**
 * 결제 승인 요청 본문(POST /v1/payments/confirm). 결제위젯 successUrl 로 받은 값을 그대로 싣는다.
 *
 * @param paymentKey 토스 결제 키(최대 200자). toString 에 싣지 않는다
 * @param orderId    결제 준비 때 우리가 만든 토스 orderId(D11)
 * @param amount     원 단위 금액. orders.total_amount(decimal(12,0)) 와 같아야 한다 — 대조는 부르는 쪽(NV-101)
 */
public record TossConfirmRequest(String paymentKey, String orderId, long amount) {

    public TossConfirmRequest {
        TossRequestRules.requirePaymentKey(paymentKey);
        TossRequestRules.requireOrderId(orderId);
        if (amount <= 0) {
            throw new IllegalArgumentException("승인 금액은 0보다 커야 한다");
        }
    }

    @Override
    public String toString() {
        return "TossConfirmRequest[paymentKey=" + TossRequestRules.MASKED + ", orderId=" + orderId
                + ", amount=" + amount + "]";
    }
}
