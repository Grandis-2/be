package com.grandis.nova.order.stock.domain.model;

import java.time.Instant;

/**
 * 보정 기록(stock_adjustments 한 행). 값이 바뀐 보정만 남긴다.
 *
 * @param requestedBy 요청한 관리자의 인증 주체 이름(관리자는 회원이 아니라 id 가 없다)
 */
public record StockAdjustment(StockCorrection correction, String reason, String requestedBy, Instant createdAt) {
}
