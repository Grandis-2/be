package com.grandis.nova.order.order.api;

import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.pay.ConfirmedPayment;

/**
 * 승인 결과. 프론트는 result 로 가른다.
 * - APPROVED: 결제 완료(orderStatus = AWAITING_CONFIRMATION 이후)
 * - DECLINED: declineReason 문구를 보이고 결제를 처음(준비)부터 다시 한다. CARD_REJECTED 는 다른 결제 수단, PAYMENT_EXPIRED 는
 *   결제창 다시 열기, FAILED 는 일반 실패 문구
 * - PENDING: 결제 확인 중(AUTHORIZING). 주문 조회(GET)로 폴링한다. 오래 머물면 같은 tossOrderId · paymentKey 로 승인을 한 번만
 *   다시 보내 결과를 회수한다
 * 오류 봉투 중 503(원인과 상관없이 모두) · 500 은 주문이 확인 중일 수 있다는 뜻이다 — 결제 준비를 다시 부르지 말고(주문이 승인
 * 중이면 409) 같은 tossOrderId · paymentKey 로 승인을 한 번 다시 보낸 뒤 주문 조회로 폴링한다. 504 · 네트워크 오류도 같다.
 * 401 은 토큰을 갱신한 뒤 같은 승인을 다시 보낸다(paymentKey 를 버리지 않는다). 409 뒤에도 주문 조회로 실제 상태를 본다.
 * 폴링은 주문 상태로 끝낸다: AWAITING_CONFIRMATION 이후 = 완료, AWAITING_PAYMENT = 거절 · 되돌림(일반 실패 문구 + 준비부터 다시),
 * CANCELING · CANCELED = 취소, AUTHORIZING = 계속(최대 대기 뒤 주문 내역 안내).
 *
 * @param declineReason DECLINED 일 때만. 결제사 원본 코드 · 문구는 싣지 않는다
 */
public record ConfirmPaymentResponse(ConfirmedPayment.Result result, OrderStatus orderStatus,
                                     DeclineReason declineReason) {

    static ConfirmPaymentResponse of(ConfirmedPayment confirmed) {
        return new ConfirmPaymentResponse(confirmed.result(), confirmed.orderStatus(), confirmed.declineReason());
    }
}
