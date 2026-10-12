package com.grandis.nova.batch.reconciliation.snapshot;

import java.time.Instant;

/**
 * 검사 스냅샷에서 읽은 be 예약 한 건과 그 외부 등록 · 취소 작업. Mock 원장과 외부 키(preorder_token)로 맞댄다.
 *
 * @param token             공개 UUID = Mock 의 외부 키
 * @param customerId        회원 id(UUID 문자열) — Mock 의 customerId 와 비교한다
 * @param productId         상품 id(UUID 문자열) — Mock 의 productId 와 비교한다
 * @param sku               신청한 옵션의 sku — Mock 의 sku 와 비교한다
 * @param registerJobStatus 등록 작업 상태. 작업이 없으면 null
 * @param cancelJobStatus   취소 작업 상태. 작업이 없으면 null
 * @param changedAt         예약 · 등록 작업 · 취소 작업 가운데 마지막으로 바뀐 시각. 판정 보류를 가를 때 쓴다
 */
public record BePreorder(String token, String id, String status, String externalReference, String customerId,
                         String productId, String sku, String registerJobStatus, String cancelJobStatus,
                         Instant changedAt) {

    public static final String PENDING_SYNC = "PENDING_SYNC";
    public static final String CANCELING = "CANCELING";
    public static final String DEAD_LETTER = "DEAD_LETTER";

    /** 등록 대기인데 등록 작업이 포기(DEAD_LETTER)됐다 — 관리자 대기 */
    public boolean registerDeadLettered() {
        return PENDING_SYNC.equals(status) && DEAD_LETTER.equals(registerJobStatus);
    }

    /** 등록 대기이고 아직 처리 중이다(포기되지 않음) */
    public boolean registering() {
        return PENDING_SYNC.equals(status) && !DEAD_LETTER.equals(registerJobStatus);
    }

    public boolean canceling() {
        return CANCELING.equals(status);
    }
}
