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
    /** 없는 주문상품 · 남의 주문상품. 남의 것도 존재를 숨기려고 같은 코드로 답한다. */
    ORDER_ITEM_NOT_FOUND(404, "주문상품을 찾을 수 없습니다."),
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
    STOCK_BELOW_COMMITTED(409, "확보 · 판매된 수량보다 적게 줄일 수 없습니다."),
    /** 사전예약 상품은 장바구니에 담지 않는다(명세 F-U-07). */
    PREORDER_NOT_CARTABLE(409, "사전예약은 장바구니에 담을 수 없습니다."),
    /**
     * 가용 재고가 모자란다. 장바구니 담기 · 수량 변경(확인만, 확보 없음 — 이 기본 문구)과 장바구니 주문(전량 확보 — 하나라도 모자라면
     * 주문을 만들지 않는다, 문구 "주문 전체 수량을 확보할 수 없습니다." · details.variantIds)이 쓴다(명세의 두 문구).
     */
    INSUFFICIENT_STOCK(409, "가용 재고보다 많이 담을 수 없습니다."),
    /** 지금 상태로는 할 수 없다 — 판매 중지 상품 담기 등. 이름 · 문구는 catalog 의 같은 코드와 같다. */
    STATE_CONFLICT(409, "현재 상태에서는 처리할 수 없습니다. 최신 상태를 조회해 주세요."),
    /** 장바구니 주문: 보낸 (옵션, 보증, 수량)이 지금 장바구니의 줄과 다르다(명세). 다시 조회해 확인한다. */
    CART_CHANGED(409, "선택한 장바구니 구성이 변경되었습니다."),
    /** 장바구니 주문: 화면에서 본 단가 · 보증가가 지금 값과 다르다. details.items 에 지금 값을 싣는다 — 확인 뒤 다시 주문한다. */
    PRICE_CHANGED(409, "가격이 변경되었습니다. 변경된 가격을 확인해 주세요.");

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
