package com.grandis.nova.order.draw;

import java.math.BigDecimal;

/**
 * 응모비 결제창을 열 값.
 *
 * @param amount    회차의 응모비
 * @param orderName 결제창에 띄울 이름 — 회차 제목(최대 100자)
 */
public record PreparedEntryPayment(String providerOrderId, BigDecimal amount, String orderName) {
}
