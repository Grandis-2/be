package com.grandis.nova.order.order.pay;

import com.grandis.nova.order.order.vo.Money;

/**
 * 결제창을 열 값.
 *
 * @param providerOrderId 결제사(토스)에 알릴 주문 번호. 결제 준비마다 새로 발급된다
 * @param amount          주문의 저장된 총액
 * @param orderName       결제창에 띄울 주문명({@link OrderNames})
 */
public record PreparedPayment(String providerOrderId, Money amount, String orderName) {
}
