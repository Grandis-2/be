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
    /** 결제 대기가 아닌 주문(결제 확인 중 · 결제됨 · 취소 중 · 취소됨 등) · 0원 주문. 지금 상태는 주문 조회로 본다. */
    ORDER_NOT_PAYABLE(409, "결제할 수 있는 주문이 아닙니다."),
    PREORDER_NOT_PAYABLE(409, "결제할 수 있는 예약이 아닙니다."),
    PAYMENT_WINDOW_EXPIRED(409, "결제 기한이 지났습니다."),
    /** 승인 요청의 금액이 주문 총액과 다르다 · 결제창이 다른 금액으로 열렸다. 사용자 금액은 기대값이 아니라 대조 대상이다(D15). */
    PAYMENT_AMOUNT_MISMATCH(409, "결제 금액이 주문 금액과 다릅니다."),
    /** 없는 결제창 · 다른 주문의 결제창. */
    PAYMENT_ATTEMPT_NOT_FOUND(404, "결제 시도를 찾을 수 없습니다."),
    /** 예약당 주문은 평생 하나라(uq_order_preorder) 취소된 주문이 있으면 다시 주문할 수 없다. */
    ORDER_ALREADY_CANCELED(409, "이미 취소된 주문이 있어 다시 주문할 수 없습니다."),
    /** 지금 상태에서 그 배송 단계로 옮길 수 없다(앞 단계 건너뛰기 · 미결제 · 취소 중 등). details.status 에 지금 상태. */
    ORDER_STEP_NOT_ALLOWED(409, "지금 상태에서 그 배송 단계로 옮길 수 없습니다."),
    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다."),
    /** 사전예약은 선점이 없어 재고 행을 두지 않는다. */
    STOCK_NOT_TRACKED(409, "사전예약 상품은 재고를 두지 않습니다."),
    /** details.options 에 걸린 옵션과 줄일 수 있는 하한(committed)을 모두 싣는다. */
    STOCK_BELOW_COMMITTED(409, "확보 · 판매된 수량보다 적게 줄일 수 없습니다.");

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
