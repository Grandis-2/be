package com.grandis.nova.preorder.outbox.publish;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 로그로만 남기는 전송. 큐 없이도 발행 흐름이 끝까지 돈다. transport=log 로 명시할 때만 켜진다 —
 * 설정이 빠지면 전송 구현이 없어 기동이 실패한다(보내지 않고 발행 완료로 표시되는 것을 막는다).
 */
@Component
@ConditionalOnProperty(name = "nova.outbox.transport", havingValue = "log")
class LoggingMessageTransport implements MessageTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingMessageTransport.class);

    @Override
    public void send(OutboundMessage message) {
        log.debug("메시지 발행 destination={} eventType={} eventId={}",
                message.destination(), message.eventType(), message.eventId());
        log.trace("메시지 본문 eventId={} body={}", message.eventId(), message.body());
    }
}
