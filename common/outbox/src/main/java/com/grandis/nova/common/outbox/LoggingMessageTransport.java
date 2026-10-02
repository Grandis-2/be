package com.grandis.nova.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 로그로만 남기는 전송(로컬 · 테스트 전용). 큐 없이도 발행 흐름이 끝까지 돈다.
 *
 * 보내지 않고 행을 발행 완료로 표시하므로 운영에서 켜지면 그 메시지는 다시 나가지 않는다(복구는 수동 SQL).
 * 그래서 켜는 조건이 둘이다: nova.outbox.transport=log 와 {@value #ALLOWED_PROPERTY}=true. 허용이 없으면 생성자가 던져
 * 기동이 실패한다. 허용 줄은 테스트 설정에만 두고 설정 예시에는 적지 않는다. 켜지면 기동 로그에 WARN 이 남는다.
 */
class LoggingMessageTransport implements MessageTransport {

    static final String ALLOWED_PROPERTY = "nova.outbox.log-transport-allowed";

    private static final Logger log = LoggerFactory.getLogger(LoggingMessageTransport.class);

    LoggingMessageTransport(boolean allowed) {
        if (!allowed) {
            throw new IllegalStateException("nova.outbox.transport=log 는 메시지를 보내지 않고 발행 완료로 표시한다 — 로컬 · 테스트 전용이다. "
                    + "쓰려면 " + ALLOWED_PROPERTY + "=true 를 함께 적는다(운영 설정에는 적지 않는다)");
        }
        log.warn("아웃박스 전송이 로그 전송기다(nova.outbox.transport=log) — 메시지를 보내지 않고 발행 완료로 표시한다. "
                + "로컬 · 테스트 전용이다");
    }

    @Override
    public void send(OutboundMessage message) {
        log.debug("메시지 발행 destination={} eventType={} eventId={}",
                message.destination(), message.eventType(), message.eventId());
        log.trace("메시지 본문 eventId={} body={}", message.eventId(), message.body());
    }
}
