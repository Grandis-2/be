package com.grandis.nova.payment.api;

import com.grandis.nova.payment.domain.enums.TargetType;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 결제창을 열 거래 요청. 금액은 호출자가 자기 저장값(주문 총액 · 응모비)으로 준다.
 *
 * 이 금액은 호출자가 주장한 값이다 — 결제는 대상의 금액을 모르고, 이 API 에 닿는 유효 토큰이면 누구나 정할 수 있다.
 * 그래서 승인 시작(PaymentLedger.start)이 호출자의 저장 금액과 다시 대조한다. 결제창 금액을 믿고 승인하지 않는다.
 *
 * 금액은 원 단위 정수 표기만 받는다(1000.0 도 400) · 양수 · 12자리 이하. 0원은 결제창을 열 수 없다.
 * VO({@link Money})가 던지는 IllegalArgumentException 은 500 이 되므로 여기서 400 으로 먼저 막는다.
 */
public record OpenCaptureRequest(
        @NotNull TargetType targetType,
        @NotNull UUID targetId,
        @NotNull @Positive @Digits(integer = 12, fraction = 0) BigDecimal amount
) {

    PaymentTarget target() {
        return new PaymentTarget(targetType, targetId);
    }

    Money money() {
        return new Money(amount);
    }
}
