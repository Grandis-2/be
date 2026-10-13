package com.grandis.nova.preorder;

import com.grandis.nova.common.ErrorCode;

/**
 * preorder 가 던지는 업무 오류. 이름 · 상태 · 문구는 계약(contracts/openapi.yaml ErrorCode)과 같다.
 * 쓰는 곳이 생길 때 추가한다.
 */
public enum PreorderErrorCode implements ErrorCode {

    ADMISSION_TICKET_REQUIRED(400, "대기열 입장권이 필요합니다."),
    SHIPMENT_BATCH_INVALID(400, "차수 구간이 겹치거나 비어 있습니다."),
    ADMISSION_TICKET_INVALID(403, "대기열에 다시 입장해 주세요."),
    /** 그 회원 · 그 모델의 마지막 접수보다 먼저 발급된 입장권. 재접수는 진입부터 다시 한다. */
    ADMISSION_TICKET_STALE(403, "대기열에 다시 입장해 주세요."),
    MEMBER_NOT_FOUND(404, "회원을 찾을 수 없습니다."),
    PREORDER_NOT_FOUND(404, "예약을 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다."),
    PRODUCT_OPTION_NOT_FOUND(404, "옵션을 찾을 수 없습니다."),
    SYNC_JOB_NOT_FOUND(404, "동기화 작업을 찾을 수 없습니다."),
    DEAD_LETTER_NOT_FOUND(404, "DLQ 메시지를 찾을 수 없습니다."),
    RECONCILIATION_RUN_NOT_FOUND(404, "정합성 검사 실행을 찾을 수 없습니다."),
    RECONCILIATION_FINDING_NOT_FOUND(404, "불일치 건을 찾을 수 없습니다."),
    SALE_NOT_OPEN(409, "아직 예약 오픈 전입니다."),
    SALE_CLOSED(409, "사전예약이 마감되었습니다."),
    ACTIVE_PREORDER_EXISTS(409, "이미 접수된 예약이 있습니다."),
    ADMISSION_TICKET_USED(409, "대기열에 다시 입장해 주세요."),
    PREORDER_NOT_CANCELABLE(409, "배송이 시작되어 취소할 수 없습니다."),
    PRODUCT_ALREADY_OPEN(409, "사전예약이 이미 오픈되어 바꿀 수 없습니다."),
    SYNC_JOB_NOT_REPROCESSABLE(409, "재처리할 수 없는 작업입니다."),
    DEAD_LETTER_NOT_REDRIVABLE(409, "되돌릴 수 없는 메시지입니다."),
    DEAD_LETTER_NOT_DISCARDABLE(409, "버릴 수 없는 메시지입니다."),
    PREORDER_FIELD_IMMUTABLE(409, "내부 메모 외에는 바꿀 수 없습니다."),
    /** 대기 · 실행 중인 검사는 하나뿐이다(reconciliation_runs.active_marker) */
    RECONCILIATION_RUN_ACTIVE(409, "대기 · 실행 중인 정합성 검사가 있습니다."),
    /** 회차 오픈 후 1시간 — 신청이 몰리는 구간이라 검사를 돌리지 않는다(batch 정기 실행도 건너뛴다) */
    RECONCILIATION_INTAKE_RUSH(409, "사전예약 오픈 직후에는 정합성 검사를 실행할 수 없습니다."),
    /** 두 실행이 모두 완료이고 검사 범위가 같아야 비교한다 */
    RECONCILIATION_NOT_COMPARABLE(409, "비교할 수 없는 실행입니다."),
    RECONCILIATION_ACTION_NOT_ALLOWED(409, "이 불일치에는 할 수 없는 조치입니다."),
    RECONCILIATION_ACTION_TAKEN(409, "이미 조치한 불일치입니다."),
    KEY_PAYLOAD_MISMATCH(422, "이전 요청과 내용이 다릅니다. 새 신청으로 다시 시도해 주세요.");

    private final int status;
    private final String message;

    PreorderErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return message;
    }
}
