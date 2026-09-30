package com.grandis.nova.payment.vo;

import java.util.Objects;

/**
 * 결제사가 발급한 결제 키(토스 paymentKey). 취소 · 조회에 쓰는 결제 식별자라 로그 · 예외 메시지에 남기지 않는다 —
 * toString 은 값을 싣지 않는다.
 */
public record ProviderPaymentKey(String value) {

    /** provider_payment_key varchar(200). */
    public static final int MAX_LENGTH = 200;

    public ProviderPaymentKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("결제 키는 1~%d자다".formatted(MAX_LENGTH));
        }
    }

    @Override
    public String toString() {
        return "ProviderPaymentKey[***]";
    }
}
