package com.grandis.nova.preorder.event;

import java.time.Instant;

/** order 가 사전예약 주문을 만들었다(결제 시작). preorderId 는 예약 공개 UUID 다. */
record PreorderPaymentStarted(String preorderId, String orderId, Instant startedAt) {
}
