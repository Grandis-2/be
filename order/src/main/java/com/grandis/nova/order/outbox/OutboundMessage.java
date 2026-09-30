package com.grandis.nova.order.outbox;

/**
 * 전송 구현에 넘기는 메시지. body 는 봉투(EventEnvelope) JSON 이다.
 *
 * common:outbox 이전 시: preorder outbox.publish.OutboundMessage 의 복사본이다. 그대로 공통으로 옮긴다.
 */
public record OutboundMessage(String destination, String eventId, String eventType, String body) {
}
