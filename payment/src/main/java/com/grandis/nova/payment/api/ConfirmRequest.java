package com.grandis.nova.payment.api;

import com.grandis.nova.payment.domain.enums.TargetType;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 승인 요청. 대상 · 금액은 호출자가 자기 저장값(주문 id · 주문 총액)으로 준다 — 사용자가 승인 요청에 실은 금액이 아니다(D15).
 * 결제 키는 결제창이 돌려준 값 그대로다.
 *
 * 모양 오류는 여기서 400 으로 막는다 — 거래를 시작한 뒤 토스 요청 규칙에 걸리면 리스를 쥔 거래가 남는다.
 * 결제 키 규칙은 토스 클라이언트와 같다: 200자 이하, 경로 세그먼트 "." · ".." 와 제어 문자 금지.
 * toString 에 결제 키를 싣지 않는다.
 *
 * @param startAllowed false 면 아직 시작하지 않은 거래를 시작하지 않고 지금 결과만 돌려준다 — 호출자가 대상을 결제할 수 없다고
 *                     판정했을 때(예약 취소 · 기한 경과) 이미 시작된 결제의 결과만 회수한다
 */
public record ConfirmRequest(
        @NotNull TargetType targetType,
        @NotNull @Positive Long targetId,
        @NotBlank @Size(max = ProviderPaymentKey.MAX_LENGTH)
        @Pattern(regexp = "^(?!\\.{1,2}$)[^\\u0000-\\u001F\\u007F-\\u009F]+$") String paymentKey,
        @NotNull @Positive @Digits(integer = 12, fraction = 0) BigDecimal amount,
        @NotNull Boolean startAllowed
) {

    PaymentTarget target() {
        return new PaymentTarget(targetType, targetId);
    }

    ProviderPaymentKey providerPaymentKey() {
        return new ProviderPaymentKey(paymentKey);
    }

    Money money() {
        return new Money(amount);
    }

    @Override
    public String toString() {
        return "ConfirmRequest[targetType=" + targetType + ", targetId=" + targetId + ", paymentKey=***, amount="
                + amount + ", startAllowed=" + startAllowed + "]";
    }
}
