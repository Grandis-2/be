package com.grandis.nova.payment.client.toss;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Payment.status(토스 문서). 토스가 값을 늘려도 응답을 읽을 수 있게 모르는 값은 {@link #UNRECOGNIZED} 로 읽는다 —
 * 역직렬화 실패로 만들면 승인이 됐는데도 "읽을 수 없는 응답" 이 된다.
 */
public enum TossPaymentStatus {
    READY,
    IN_PROGRESS,
    /** 가상계좌 입금 대기. 가상계좌는 결제위젯에서 끈다(D7) — 승인 응답으로 오면 결과 불명이다. */
    WAITING_FOR_DEPOSIT,
    DONE,
    CANCELED,
    PARTIAL_CANCELED,
    ABORTED,
    EXPIRED,
    UNRECOGNIZED;

    @JsonCreator
    public static TossPaymentStatus from(String value) {
        if (value != null) {
            for (TossPaymentStatus status : values()) {
                if (status != UNRECOGNIZED && status.name().equals(value)) {
                    return status;
                }
            }
        }
        return UNRECOGNIZED;
    }
}
