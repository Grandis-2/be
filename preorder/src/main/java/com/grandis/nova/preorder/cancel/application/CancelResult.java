package com.grandis.nova.preorder.cancel.application;

import com.grandis.nova.preorder.preorder.PreorderStatus;

/**
 * 취소 요청 결과(openapi PreorderCancelAccepted).
 *
 * @param version 늦게 도착한 이전 응답을 버리는 데 쓴다(preorders.event_sequence)
 */
public record CancelResult(String preorderId, PreorderStatus status, long version) {
}
