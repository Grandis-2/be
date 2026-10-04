package com.grandis.nova.catalog.edit;

import jakarta.validation.constraints.NotNull;

/**
 * PATCH /admin/products/{id}/visibility. 공개 ↔ 비공개 — 언제든 바꾼다(사전예약 오픈 뒤에도). 기존 예약 · 주문은 그대로다.
 *
 * @param visible true 면 공개. 회원 노출은 판매 방식별 준비 · 판매 상태와 함께 정해진다
 */
public record VisibilityChangeRequest(@NotNull Boolean visible) {
}
