package com.grandis.nova.order.order.api;

import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.pay.ConfirmedPayment;

/**
 * 승인 결과. 프론트는 result 로 가른다.
 * - APPROVED: 결제 완료(orderStatus = AWAITING_CONFIRMATION 이후).
 * - DECLINED: declineReason 문구를 보이고 결제를 처음(준비)부터 다시 한다. CARD_REJECTED 는 다른 결제 수단, PAYMENT_EXPIRED 는
 *   결제창 다시 열기, FAILED 는 일반 실패 문구
 * - PENDING: 결제 확인 중(AUTHORIZING). 주문 조회(GET)로 폴링한다. 오래 머물면 같은 tossOrderId · paymentKey 로 승인을 한 번만
 *   다시 보내 결과를 회수한다
 * 오류 봉투 중 503(원인과 상관없이 모두) · 500 은 주문이 확인 중일 수 있다는 뜻이다 — 결제 준비를 다시 부르지 말고(주문이 승인
 * 중이면 409) 같은 tossOrderId · paymentKey 로 승인을 한 번 다시 보낸 뒤 주문 조회로 폴링한다. 504 · 네트워크 오류도 같다.
 * 401 은 토큰을 갱신한 뒤 같은 승인을 다시 보낸다(paymentKey 를 버리지 않는다). 409 뒤에도 주문 조회로 실제 상태를 본다.
 * 409 ORDER_NOT_PAYABLE 은 취소 중 · 취소된 주문이다 — 승인을 기다리는 사이 취소가 됐으면(만든 뒤 20분이 지나 결과를 몰라 취소) 결제사 답이
 * 승인이든 거절이든 APPROVED · DECLINED 대신 이것이고, 이미 결제돼 취소 중으로 간 주문에 승인을 다시 보내도 이것이다. 이 409 뒤에는 주문 조회
 * (GET /api/v1/orders/{orderId})로 상태와 이력을 보고 안내한다 — 승인 중 → 취소 이력의 사유가 PAYMENT_UNCONFIRMED 면 결제 확인 실패로
 * 취소된 주문이고, 결제됐다면 자동으로 환불된다. 그 뒤에 취소 → 취소 이력(자동 환불 요청 PAYMENT_LATE_REFUND · 환불 실패 REFUND_FAILED)이
 * 더 붙을 수 있으므로 마지막 이력만 보지 않는다.
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
