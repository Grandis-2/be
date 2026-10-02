package com.grandis.nova.order.order.pay;

import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.order.domain.enums.OrderStatus;

import java.util.Objects;

/**
 * 승인 요청의 결과.
 *
 * @param orderStatus   반영 뒤 주문 상태
 * @param declineReason DECLINED 일 때만
 */
public record ConfirmedPayment(Result result, OrderStatus orderStatus, DeclineReason declineReason) {

    public ConfirmedPayment {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(orderStatus, "orderStatus");
        if ((result == Result.DECLINED) != (declineReason != null)) {
            throw new IllegalArgumentException("거절일 때만 사유가 있다: result=" + result);
        }
    }

    static ConfirmedPayment approved(OrderStatus orderStatus) {
        return new ConfirmedPayment(Result.APPROVED, orderStatus, null);
    }

    static ConfirmedPayment pending() {
        return new ConfirmedPayment(Result.PENDING, OrderStatus.AUTHORIZING, null);
    }

    public enum Result {
        APPROVED,
        DECLINED,
        /** 결제 확인 중(처리 중 · 결과 불명 · 응답을 못 받음). 같은 요청을 다시 보내거나 주문을 조회한다. */
        PENDING
    }
}
