package com.grandis.nova.order.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 로그로만 남기는 전송. 큐 없이도 발행 흐름이 끝까지 돈다. transport=log 로 명시할 때만 켜진다 —
 * 설정이 빠지면 전송 구현이 없어 기동이 실패한다(보내지 않고 발행 완료로 표시되는 것을 막는다).
 *
 * 보내지 않고도 행을 발행 완료로 표시하므로, 운영에서 켜지면 그 메시지는 다시 나가지 않는다(릴레이도 집지 않는다).
 * 켜질 때 WARN 을 남겨 설정 실수가 기동 로그에 드러나게 한다. 운영 프로필에서 기동을 막지는 않는다 — 이 저장소에는
 * 운영 프로필 이름이 정해져 있지 않고, 로컬 · 테스트 기동을 깨지 않으려면 막을 기준이 없다.
 *
 * common:outbox 이전 시: preorder outbox.publish.LoggingMessageTransport 의 복사본이다. 기동 WARN 생성자는 order 에만
 * 있다 — 공통으로 옮길 때 유지한다.
 */
@Component
@ConditionalOnProperty(name = "nova.outbox.transport", havingValue = "log")
class LoggingMessageTransport implements MessageTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingMessageTransport.class);

    LoggingMessageTransport() {
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
