package com.grandis.nova.order.draw.domain.model;

/**
 * 응모 상태 변경의 결과.
 *
 * @param applied 이번 변경이 반영됐는가 — 전제(지금 상태 · 결제창)가 맞지 않으면 false
 * @param status  변경 뒤(반영되지 않았으면 지금)의 상태
 */
public record EntryTransition(boolean applied, DrawEntryStatus status) {
}
