package com.grandis.nova.order.client.payment;

/** payment DLQ 행의 실패 분류(payment deadletter.domain.FailureReason 과 이름이 같아야 한다). 요청 조건을 가르는 데만 쓴다. */
public enum PaymentDeadLetterFailureReason {
    UNREADABLE_BODY,
    UNKNOWN_EVENT_TYPE,
    PROCESSING_FAILED
}
