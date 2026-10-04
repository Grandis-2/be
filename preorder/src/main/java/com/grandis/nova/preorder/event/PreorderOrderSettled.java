package com.grandis.nova.preorder.event;

import java.time.Instant;

/**
 * order 가 예약 취소에 따른 주문 정리를 끝냈다(계약 2.3). result 는 order 의 판정이라 payload 를 믿는다(I-3).
 *
 * @param reason         REJECTED 일 때 SHIPPED · PAID(만료 경합)
 * @param cancelSequence PREORDER_CANCEL_REQUESTED 에서 받은 값 그대로. 어느 취소 시도의 결과인지 가린다
 * @param paidAt         REJECTED · PAID 일 때 주문의 결제 시각. 없으면 null
 */
record PreorderOrderSettled(String preorderId, Result result, String reason, Long cancelSequence, Instant paidAt) {

    /** 시도를 가릴 수 없는 결과는 반영하지 않고 실패로 올린다 — 조용히 버리면 예약이 취소 중에 멈춘다. */
    public PreorderOrderSettled {
        if (cancelSequence == null) {
            throw new IllegalArgumentException("cancelSequence 가 없다: preorderId=" + preorderId);
        }
    }

    /** 주문 정리 결과. 미결제 주문 취소와 결제 주문 환불 완료는 둘 다 CANCELED 로 온다. */
    public enum Result {
        NO_ORDER,
        CANCELED,
        REJECTED
    }
}
