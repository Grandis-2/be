package com.grandis.nova.preorder.event;

import java.time.Instant;

/** order 가 사전예약 주문의 결제를 승인했다. paidAt 은 order 가 확정한 값이라 그대로 믿는다. */
record PreorderPaymentConfirmed(String preorderId, String orderId, Instant paidAt) {
}
