package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.domain.model.Order;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.Instant;

/**
 * 결제 결과를 끝내 모르는 장바구니 · 바로 구매 주문을 취소하는 작업의 설정({@link UnconfirmedCartOrderExpiry}). 꺼 둔 환경에서도 바인딩할 때
 * 범위를 검사한다 — 잘못된 값이 켜는 날 처음 드러나지 않게.
 *
 * @param enabled  켜면 취소한다. 기본은 꺼짐 — 배포 뒤 켠다. 끄면 새 취소만 멈춘다 — 이미 이렇게 취소한 주문의 늦은 승인 자동 환불
 *                 (PaymentResults)은 이 작업을 거치지 않으므로 스위치와 상관없이 동작한다
 * @param after    주문을 만든 뒤 이만큼 지나도 승인 중이면 취소한다. 결제 기한({@link Order#PAYMENT_WINDOW})보다 길어야 한다 —
 *                 그 안의 승인 중은 사용자가 결제를 마치는 중이다
 * @param interval 고르는 주기(앞 실행이 끝난 뒤부터 잰다)
 */
@ConfigurationProperties("nova.order.payment-unconfirmed")
record UnconfirmedPaymentProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("20m") Duration after,
        @DefaultValue("30s") Duration interval
) {

    UnconfirmedPaymentProperties {
        if (after == null || after.compareTo(Order.PAYMENT_WINDOW) <= 0) {
            throw new IllegalArgumentException("결과를 모른 주문 취소 시간은 결제 기한(" + Order.PAYMENT_WINDOW + ")보다 길어야 한다: " + after);
        }
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("결과를 모른 주문 취소 주기는 0 보다 커야 한다: " + interval);
        }
    }

    /** 결제 기한이 이 시각 이하면 만든 지 after 가 지났다 — 기한은 만든 때 + 결제 기한이다. */
    Instant dueBefore(Instant now) {
        return now.minus(after.minus(Order.PAYMENT_WINDOW));
    }
}
