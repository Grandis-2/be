package com.grandis.nova.payment.domain.enums;

/**
 * 결제 거래의 종류. CAPTURE 는 결제창을 열 때마다 새 행(새 결제사 주문 번호), REFUND 는 환불마다 한 행이다.
 * 한 행의 재전송 · 재시도는 모두 그 행에서 한다(멱등 키 교체가 필요해져도 같은 행 — {@link com.grandis.nova.payment.vo.IdempotencyKey}).
 */
public enum TransactionType {
    /** 결제 승인. */
    CAPTURE,
    /** 결제 취소(환불). */
    REFUND
}
