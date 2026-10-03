package com.grandis.nova.order.order.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 결제위젯 successUrl 로 받은 값. 금액은 주문 총액과 대조만 한다 — 결제 기대값은 주문의 저장 총액이다(D15).
 * 결제 키 규칙은 payment 와 같다(200자 이하, "." · ".." · 제어 문자 금지) — 여기서 400 으로 먼저 막는다. toString 에 결제 키를 싣지 않는다.
 *
 * @param paymentKey 토스 결제 키
 * @param amount     결제창이 알린 금액(원)
 */
public record ConfirmPaymentRequest(
        @NotBlank @Size(max = 200) @Pattern(regexp = "^(?!\\.{1,2}$)[^\\u0000-\\u001F\\u007F-\\u009F]+$")
        String paymentKey,
        @NotNull @Positive @Digits(integer = 12, fraction = 0) BigDecimal amount
) {

    @Override
    public String toString() {
        return "ConfirmPaymentRequest[paymentKey=***, amount=" + amount + "]";
    }
}
