package com.grandis.nova.preorder.preorder;

/**
 * 결제 기한이 지난 REGISTERED 예약 수.
 *
 * @param overdue 만료 작업이 집을 수
 * @param blocked   주문 쪽에서 취소가 거절된 적이 있어 만료 대상에서 빼 둔 수. 0 보다 크면 사람이 본다
 */
public record PaymentOverdue(long overdue, long blocked) {
}
