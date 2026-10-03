package com.grandis.nova.common.outbox;

/** 전송 구현에 넘기는 메시지. destination 은 논리 목적지, body 는 봉투(EventEnvelope) JSON 이다. */
public record OutboundMessage(String destination, String eventId, String eventType, String body) {
}
