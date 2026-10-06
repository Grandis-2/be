package com.grandis.nova.payment.client.toss;

import java.time.Instant;
import java.util.List;

/**
 * 토스 Payment 객체 중 결제 · 환불 처리에 쓰는 필드. 나머지 필드(카드 · 영수증 등)는 읽지 않는다.
 *
 * @param paymentKey         결제 키. toString 에 싣지 않는다
 * @param orderId            우리가 만든 토스 orderId
 * @param status             모르는 값은 UNRECOGNIZED
 * @param totalAmount        최초 결제 금액(취소돼도 유지)
 * @param balanceAmount      취소할 수 있는 잔액. 전액 취소 뒤 0
 * @param method             결제수단(한글, 예: "카드", "간편결제"). 승인 전이면 null
 * @param approvedAt         승인 시각. 승인 전이면 null
 * @param lastTransactionKey 마지막 거래(승인 · 취소) 키. null 일 수 있다
 * @param cancels            취소 내역(취소 시각 · 금액). 취소 전이면 비어 있다 — 토스는 null 로 준다
 */
public record TossPayment(
        String paymentKey,
        String orderId,
        TossPaymentStatus status,
        long totalAmount,
        long balanceAmount,
        String method,
        Instant approvedAt,
        String lastTransactionKey,
        List<Cancel> cancels
) {

    /** status 필드가 없거나 null 이면 역직렬화가 {@link TossPaymentStatus#from} 을 거치지 않는다 — 여기서 같은 규칙을 적용한다. */
    public TossPayment {
        if (status == null) {
            status = TossPaymentStatus.UNRECOGNIZED;
        }
        cancels = cancels == null ? List.of() : List.copyOf(cancels);
    }

    /**
     * 취소 내역 한 건. 나머지 필드(사유 · 거래 키 · 할인 금액 등)는 읽지 않는다.
     *
     * @param canceledAt   취소 시각. 없으면 null — 받는 쪽이 계약 위반으로 다룬다
     * @param cancelAmount 이 취소로 돌려준 금액
     */
    public record Cancel(Instant canceledAt, long cancelAmount) {
    }

    @Override
    public String toString() {
        return "TossPayment[paymentKey=" + TossRequestRules.MASKED + ", orderId=" + orderId
                + ", status=" + status + ", totalAmount=" + totalAmount + ", balanceAmount=" + balanceAmount
                + ", method=" + method + ", approvedAt=" + approvedAt + ", lastTransactionKey=" + lastTransactionKey
                + ", cancels=" + cancels + "]";
    }
}
