package com.grandis.nova.order.client.payment;

/** payment DLQ 행의 처리 상태(payment deadletter.domain.DeadLetterStatus 와 이름이 같아야 한다). 목록 조건을 가르는 데만 쓴다. */
public enum PaymentDeadLetterStatus {
    OPEN,
    REDRIVING,
    REDRIVEN,
    SUCCEEDED,
    REDRIVE_FAILED,
    DISCARDED
}
