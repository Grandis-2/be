package com.grandis.nova.order;

import com.grandis.nova.common.ErrorCode;

/**
 * order 가 던지는 업무 오류. 이름 · 상태 · 문구는 계약(contracts/openapi.yaml ErrorCode)과 같아야 한다 — 계약 확정 전 초안이다.
 * 쓰는 곳이 생길 때 추가한다.
 */
public enum OrderErrorCode implements ErrorCode {

    /** 없는 예약 · 남의 예약. 남의 예약도 존재를 숨기려고 같은 코드로 답한다. */
    PREORDER_NOT_FOUND(404, "예약을 찾을 수 없습니다."),
    /** 없는 주문 · 남의 주문. */
    ORDER_NOT_FOUND(404, "주문을 찾을 수 없습니다."),
    PREORDER_NOT_PAYABLE(409, "결제할 수 있는 예약이 아닙니다."),
    PAYMENT_WINDOW_EXPIRED(409, "결제 기한이 지났습니다."),
    /** 예약당 주문은 평생 하나라(uq_order_preorder) 취소된 주문이 있으면 다시 주문할 수 없다. */
    ORDER_ALREADY_CANCELED(409, "이미 취소된 주문이 있어 다시 주문할 수 없습니다.");

    private final int status;
    private final String message;

    OrderErrorCode(int status, String message) {
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
