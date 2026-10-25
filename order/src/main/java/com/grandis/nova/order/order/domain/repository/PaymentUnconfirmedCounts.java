package com.grandis.nova.order.order.domain.repository;

/**
 * 결제 결과를 끝내 몰라 취소한 장바구니 · 바로 구매 주문의 수(취소한 시각 기준). 늦게 승인돼 자동 환불한 주문도 든다.
 *
 * @param last1h  최근 1시간
 * @param last24h 최근 24시간(1시간 것을 포함한다)
 */
public record PaymentUnconfirmedCounts(long last1h, long last24h) {
}
