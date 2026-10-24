package com.grandis.nova.preorder.reconciliation.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 불일치 한 건.
 *
 * @param targetKey    예약 · Mock 건은 외부 키, 순번 빈틈은 상품 id
 * @param productId    불일치 대상의 상품. 알 수 없거나 상품 칸이 생기기 전 결과면 null
 * @param beSnapshot   검사 때 본 be 값(JSON). 없으면 null
 * @param mockSnapshot 검사 때 본 Mock 원장 행(JSON). 없으면 null
 */
public record FindingRow(UUID id, UUID runId, String checkCode, String targetKey, UUID productId, String beSnapshot,
                         String mockSnapshot, Instant firstDetectedAt, ActionStatus actionStatus, ActionType actionType,
                         String actionRequestedBy, Instant actionRequestedAt, String actionNote) {
}
