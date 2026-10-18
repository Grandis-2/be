package com.grandis.nova.order.draw.domain.model;

/**
 * 내 응모 목록 탭의 건수.
 *
 * @param all 내 응모 전부(결제 대기 · 미결제 마감 포함)
 * @param won 당첨 주문이 이어진 응모
 */
public record MyDrawEntryCounts(long all, long won) {
}
