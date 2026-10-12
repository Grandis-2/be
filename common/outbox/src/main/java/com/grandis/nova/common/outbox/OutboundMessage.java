package com.grandis.nova.common.outbox;

import java.util.Map;

/**
 * 전송 구현에 넘기는 메시지. destination 은 논리 목적지, body 는 봉투(EventEnvelope) JSON 이다.
 * attributes 는 본문 밖에 싣는 추가 값이다({@link OutboundAttributes}). 종류 · id 는 전송 구현이 따로 싣는다.
 */
public record OutboundMessage(String destination, String eventId, String eventType, String body,
                              Map<String, String> attributes) {

    public OutboundMessage {
        attributes = Map.copyOf(attributes);
    }

    public OutboundMessage(String destination, String eventId, String eventType, String body) {
        this(destination, eventId, eventType, body, Map.of());
    }
}
