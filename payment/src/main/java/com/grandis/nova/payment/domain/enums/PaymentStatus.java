package com.grandis.nova.payment.domain.enums;

/** 성공한 결제(payments)의 상태. 실패한 시도는 payments 에 없다. */
public enum PaymentStatus {
    SUCCEEDED,
    REFUNDED
}
