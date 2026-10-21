package com.grandis.nova.batch.reconciliation.mock;

import java.time.Instant;

/**
 * Mock 원장 목록의 한 행(GET /external/reservations 의 items 한 건). 이름은 Mock 응답 그대로다.
 * 등록 전에 취소만 들어온 키(취소 표식)는 번호 · 회원 · 상품 · sku · 확정 시각이 null 이다.
 *
 * @param customerId 소문자 표준 UUID 문자열(be 회원 id)
 * @param productId  소문자 표준 UUID 문자열(be 상품 id)
 * @param status     ACTIVE · CANCELED
 */
public record MockLedgerEntry(String externalKey, String externalNumber, String customerId, String productId,
                              String sku, String status, Instant confirmedAt, Instant canceledAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String CANCELED = "CANCELED";

    public boolean active() {
        return ACTIVE.equals(status);
    }

    /** 등록 전에 취소만 들어온 키 — 번호가 없다 */
    public boolean cancelMarker() {
        return CANCELED.equals(status) && externalNumber == null;
    }

    /** 이 행이 마지막으로 바뀐 시각. 판정 보류를 가를 때 쓴다 */
    public Instant changedAt() {
        if (confirmedAt == null) {
            return canceledAt;
        }
        return canceledAt == null || confirmedAt.isAfter(canceledAt) ? confirmedAt : canceledAt;
    }
}
