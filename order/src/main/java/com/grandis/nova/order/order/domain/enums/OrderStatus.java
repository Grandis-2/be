package com.grandis.nova.order.order.domain.enums;

import java.util.Optional;

/**
 * 주문의 생애주기이자 상태 머신(ck_order_status).
 *
 * <pre>
 * 결제   AWAITING_PAYMENT ─PAYMENT_REQUESTED─▶ AUTHORIZING ─PAYMENT_APPROVED─▶ AWAITING_CONFIRMATION
 *                      ◀──────PAYMENT_DECLINED──────┘
 * 배송   AWAITING_CONFIRMATION ─PREPARATION_STARTED─▶ PREPARING_ITEMS ─PACKED─▶ READY_TO_SHIP
 *                              ─SHIPPED─▶ SHIPPED ─DELIVERED─▶ DELIVERED
 * 취소   AWAITING_PAYMENT ─CANCEL_REQUESTED─▶ CANCELED                    (미결제라 환불 없음)
 *        AWAITING_CONFIRMATION · PREPARING_ITEMS · READY_TO_SHIP
 *                         ─CANCEL_REQUESTED─▶ CANCELING ─REFUND_COMPLETED─▶ CANCELED
 * </pre>
 *
 * 이번 에픽에서 호출되는 것은 생성과 AWAITING_PAYMENT → CANCELED 뿐이다. 결제 · 환불 · 배송 전이는
 * payment 에픽에서 상태 머신을 다시 뜯지 않도록 지금 적어 둔다.
 */
public enum OrderStatus {

    /** 생성됨. 결제 전. */
    AWAITING_PAYMENT,
    /** 결제 요청 중. 결과를 아직 모른다. */
    AUTHORIZING,
    /** 결제 완료. 판매자 확인 대기. */
    AWAITING_CONFIRMATION,
    PREPARING_ITEMS,
    READY_TO_SHIP,
    SHIPPED,
    DELIVERED,
    /** 결제된 주문의 환불 중. */
    CANCELING,
    CANCELED;

    /**
     * 이 상태에서 그 사건이 일어났을 때의 다음 상태. 의미 없는 사건이면 비어 있다 —
     * 같은 메시지를 두 번 받았거나(CANCELED 에 취소 요청), 받아들일 수 없는 사건(출고 뒤 취소 요청)이다.
     * 둘을 여기서 구분하지 않는다. 호출하는 쪽이 지금 상태를 보고 판단한다.
     *
     * 상태 · 사건 조합을 모두 적는다. default 로 뭉뚱그리지 않아서 상태나 사건이 늘면 컴파일러가 빠진 곳을 알린다.
     */
    public Optional<OrderStatus> next(OrderTrigger trigger) {
        return switch (this) {
            case AWAITING_PAYMENT -> switch (trigger) {
                case CANCEL_REQUESTED -> Optional.of(CANCELED);
                case PAYMENT_REQUESTED -> Optional.of(AUTHORIZING);
                case PAYMENT_APPROVED, PAYMENT_DECLINED, REFUND_COMPLETED,
                     PREPARATION_STARTED, PACKED, SHIPPED, DELIVERED -> Optional.empty();
            };
            // 결제 결과를 모르는 동안의 취소 요청은 받지 않는다. 결과가 나온 뒤 다시 판정한다.
            case AUTHORIZING -> switch (trigger) {
                case PAYMENT_APPROVED -> Optional.of(AWAITING_CONFIRMATION);
                case PAYMENT_DECLINED -> Optional.of(AWAITING_PAYMENT);
                case CANCEL_REQUESTED, PAYMENT_REQUESTED, REFUND_COMPLETED,
                     PREPARATION_STARTED, PACKED, SHIPPED, DELIVERED -> Optional.empty();
            };
            case AWAITING_CONFIRMATION -> switch (trigger) {
                case CANCEL_REQUESTED -> Optional.of(CANCELING);
                case PREPARATION_STARTED -> Optional.of(PREPARING_ITEMS);
                case PAYMENT_REQUESTED, PAYMENT_APPROVED, PAYMENT_DECLINED, REFUND_COMPLETED,
                     PACKED, SHIPPED, DELIVERED -> Optional.empty();
            };
            case PREPARING_ITEMS -> switch (trigger) {
                case CANCEL_REQUESTED -> Optional.of(CANCELING);
                case PACKED -> Optional.of(READY_TO_SHIP);
                case PAYMENT_REQUESTED, PAYMENT_APPROVED, PAYMENT_DECLINED, REFUND_COMPLETED,
                     PREPARATION_STARTED, SHIPPED, DELIVERED -> Optional.empty();
            };
            case READY_TO_SHIP -> switch (trigger) {
                case CANCEL_REQUESTED -> Optional.of(CANCELING);
                case SHIPPED -> Optional.of(OrderStatus.SHIPPED);
                case PAYMENT_REQUESTED, PAYMENT_APPROVED, PAYMENT_DECLINED, REFUND_COMPLETED,
                     PREPARATION_STARTED, PACKED, DELIVERED -> Optional.empty();
            };
            case SHIPPED -> switch (trigger) {
                case DELIVERED -> Optional.of(OrderStatus.DELIVERED);
                case CANCEL_REQUESTED, PAYMENT_REQUESTED, PAYMENT_APPROVED, PAYMENT_DECLINED, REFUND_COMPLETED,
                     PREPARATION_STARTED, PACKED, SHIPPED -> Optional.empty();
            };
            case CANCELING -> switch (trigger) {
                case REFUND_COMPLETED -> Optional.of(CANCELED);
                case CANCEL_REQUESTED, PAYMENT_REQUESTED, PAYMENT_APPROVED, PAYMENT_DECLINED,
                     PREPARATION_STARTED, PACKED, SHIPPED, DELIVERED -> Optional.empty();
            };
            case DELIVERED -> switch (trigger) {
                case CANCEL_REQUESTED, PAYMENT_REQUESTED, PAYMENT_APPROVED, PAYMENT_DECLINED, REFUND_COMPLETED,
                     PREPARATION_STARTED, PACKED, SHIPPED, DELIVERED -> Optional.empty();
            };
            case CANCELED -> switch (trigger) {
                case CANCEL_REQUESTED, PAYMENT_REQUESTED, PAYMENT_APPROVED, PAYMENT_DECLINED, REFUND_COMPLETED,
                     PREPARATION_STARTED, PACKED, SHIPPED, DELIVERED -> Optional.empty();
            };
        };
    }

    /**
     * 취소를 받아들일 수 있는가. 출고 뒤(SHIPPED · DELIVERED)만 아니다.
     * 취소 가능 조회 API 가 쓴다. 예약 취소 수신의 배송 거절(SettlePreorderCancelService.settledFor)은 상태 switch 로
     * 따로 적혀 있다 — 두 곳이 어긋나면 조회는 "가능" 이라 하고 실제 취소는 거절하므로 CancelabilityAgreementTest 가 맞물림을 본다.
     * 이미 취소됐거나 취소 중인 주문도 true 다(다시 취소해도 결과가 같다).
     */
    public boolean isCancelable() {
        return this != SHIPPED && this != DELIVERED;
    }
}
