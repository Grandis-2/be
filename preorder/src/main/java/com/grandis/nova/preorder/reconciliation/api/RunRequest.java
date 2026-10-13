package com.grandis.nova.preorder.reconciliation.api;

import java.util.UUID;

/** @param productId 검사 범위 — 사전예약 상품 하나. 비우면 전체 */
record RunRequest(UUID productId) {
}
