package com.grandis.nova.waitingroom;

import com.grandis.nova.common.ErrorCode;

/**
 * 대기열이 직접 답하는 업무 오류. 이름 · 상태 · 문구는 API 계약과 같고, preorder 와 겹치는 것은 같은 값이다 —
 * 클라이언트는 어느 쪽이 거절했는지 몰라도 같은 이름으로 분기한다.
 */
public enum WaitingroomErrorCode implements ErrorCode {

    ADMISSION_TICKET_REQUIRED(400, "대기열 입장권이 필요합니다."),
    ADMISSION_TICKET_INVALID(403, "대기열에 다시 입장해 주세요."),
    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다."),
    SALE_NOT_OPEN(409, "아직 예약 오픈 전입니다."),
    SALE_CLOSED(409, "사전예약이 마감되었습니다."),
    QUEUE_FULL(429, "대기열이 가득 찼습니다."),
    ADMISSION_RATE_UNREACHABLE(400, "사전예약 서버를 최대로 늘려도 받을 수 없는 입장 속도입니다."),
    ADMISSION_RATE_NEEDS_CONFIRM(409, "사전예약 서버가 늘어난 뒤에 반영되는 입장 속도입니다. 확인 후 다시 요청해 주세요.");

    private final int status;
    private final String message;

    WaitingroomErrorCode(int status, String message) {
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
