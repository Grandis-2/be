package com.grandis.nova.order.order.pay;

import com.grandis.nova.order.client.payment.DeclineReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * payment 가 알린 확정 결과(결과 이벤트). 동기 응답을 잃었을 때 주문이 결과를 받는 길이다.
 *
 * @param amount        거래 금액. 주문 총액과 대조만 한다(다르면 ERROR — 반영은 한다, 돈의 사실은 결제 쪽이다)
 * @param approvedAt    APPROVED 일 때만. 결제사 승인 시각 — 이 결과로 승인을 반영하면 주문의 결제 시각이 된다
 * @param declineReason DECLINED 일 때만
 */
public record PaymentSettlement(UUID orderId, String providerOrderId, Result result, BigDecimal amount,
                                Instant approvedAt, DeclineReason declineReason) {

    public PaymentSettlement {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(amount, "amount");
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new IllegalArgumentException("결제창 번호가 없다: orderId=" + orderId);
        }
        if ((result == Result.DECLINED) != (declineReason != null)) {
            throw new IllegalArgumentException("거절일 때만 사유가 있다: orderId=" + orderId + ", result=" + result);
        }
        if ((result == Result.APPROVED) != (approvedAt != null)) {
            throw new IllegalArgumentException("승인일 때만 승인 시각이 있다: orderId=" + orderId + ", result=" + result);
        }
    }

    public enum Result {
        APPROVED,
        DECLINED
    }
}
