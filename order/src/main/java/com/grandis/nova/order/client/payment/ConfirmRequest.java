package com.grandis.nova.order.client.payment;

import com.grandis.nova.order.order.domain.model.Order;

import java.math.BigDecimal;

/**
 * payment ConfirmRequest 와 같은 모양. 대상 · 금액은 주문에서 읽는다 — 금액(BigDecimal)을 따로 받지 않는 것이 구조적 가드다(D15):
 * 프론트가 보낸 금액과 저장 총액이 한 메서드에 섞여도 사용자 금액을 결제 기대값으로 넘길 수 없다.
 * toString 에 결제 키를 싣지 않는다.
 *
 * @param amount       주문의 저장된 총액(orders.total_amount)
 * @param startAllowed false 면 payment 는 아직 시작하지 않은 결제창을 시작하지 않고 지금 결과만 돌려준다
 */
public record ConfirmRequest(String targetType, Long targetId, String paymentKey, BigDecimal amount,
                             boolean startAllowed) {

    public static ConfirmRequest of(Order order, String paymentKey, boolean startAllowed) {
        return new ConfirmRequest(CaptureRequest.ORDER, order.id(), paymentKey, order.totalAmount().amount(),
                startAllowed);
    }

    @Override
    public String toString() {
        return "ConfirmRequest[targetType=" + targetType + ", targetId=" + targetId + ", paymentKey=***, amount="
                + amount + ", startAllowed=" + startAllowed + "]";
    }
}
