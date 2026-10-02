package com.grandis.nova.preorder.event;

/** batch 가 결제 기한이 지난 예약의 만료를 요청했다. */
record PreorderExpiryRequested(String preorderId) {
}
