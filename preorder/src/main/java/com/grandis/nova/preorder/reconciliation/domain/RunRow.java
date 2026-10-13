package com.grandis.nova.preorder.reconciliation.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 검사 실행 한 줄.
 *
 * @param requestedBy 실행을 요청한 관리자. 비어 있으면 정기 실행
 * @param productId   검사 범위. 비어 있으면 전체
 * @param cutoffAt    판정 기준 시각(시작 − 여유). 이 뒤에 바뀐 키는 판정하지 않고 heldCount 로만 셌다
 * @param heldCount   판정 보류 건수. 완료 전에는 비어 있다(아래 셋도 같다)
 */
public record RunRow(UUID id, String requestedBy, UUID productId, RunStatus status, Instant createdAt,
                     Instant startedAt, Instant cutoffAt, Instant finishedAt, String errorMessage, Integer heldCount,
                     Integer pendingSyncCount, Integer cancelingCount, Integer deadLetterCount) {
}
