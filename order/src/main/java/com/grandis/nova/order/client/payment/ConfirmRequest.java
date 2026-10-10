package com.grandis.nova.order.client.payment;


import java.math.BigDecimal;
import java.util.UUID;

/**
 * payment ConfirmRequest 와 같은 모양. 대상 · 금액은 저장된 대상에서 읽는다({@link PayableTarget} — 구조적 가드).
 * toString 에 결제 키를 싣지 않는다.
 *
 * @param amount       대상의 저장 금액(주문 총액 · 응모비)
 * @param startAllowed false 면 payment 는 아직 시작하지 않은 결제창을 시작하지 않고 지금 결과만 돌려준다
 * @param reserve      결제창 확인(시작 금지)과 함께 아직 시작 전인 결제창을 확보한다 — 승인 중으로 바꾸기 직전의 확인에서만
 */
public record ConfirmRequest(String targetType, UUID targetId, String paymentKey, BigDecimal amount,
                             boolean startAllowed, boolean reserve) {

    public static ConfirmRequest of(PayableTarget target, String paymentKey, boolean startAllowed) {
        return new ConfirmRequest(target.type(), target.id(), paymentKey, target.amount(), startAllowed, false);
    }

    /** 승인 중으로 바꾸기 직전의 결제창 확인: 시작하지 않고 확보한다(payment 의 만료가 그 전환 사이에 끼지 않게). */
    public static ConfirmRequest check(PayableTarget target, String paymentKey) {
        return new ConfirmRequest(target.type(), target.id(), paymentKey, target.amount(), false, true);
    }

    @Override
    public String toString() {
        return "ConfirmRequest[targetType=" + targetType + ", targetId=" + targetId + ", paymentKey=***, amount="
                + amount + ", startAllowed=" + startAllowed + ", reserve=" + reserve + "]";
    }
}
