package com.grandis.nova.order.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 로그로만 남기는 전송(로컬 · 테스트 전용). 큐 없이도 발행 흐름이 끝까지 돈다.
 *
 * 보내지 않고 행을 발행 완료로 표시하므로 운영에서 켜지면 그 메시지는 다시 나가지 않는다 — 릴레이도 집지 않아 복구는 수동 SQL 이고,
 * preorder 예약은 취소 중에서 멈춘다. 그래서 켜는 조건이 둘이다: nova.outbox.transport=log 와 {@value #ALLOWED_PROPERTY}=true.
 * 허용이 없으면 생성자가 던져 기동이 실패한다. 허용 줄은 테스트 설정에만 두고 application.yml.example 에는 적지 않는다 —
 * 예시에서 만든 운영 설정은 transport 값이 log 로 바뀌어도 뜨지 않는다. 켜지면 기동 로그에 WARN 이 남는다.
 * 운영 프로필 이름으로 막지 않는 것은 이 저장소에 프로필 이름이 정해져 있지 않아서다.
 *
 * common:outbox 이전 시: preorder outbox.publish.LoggingMessageTransport 의 복사본이다. 기동 WARN 생성자는 order 에만
 * 있다 — 공통으로 옮길 때 유지한다.
 */
@Component
@ConditionalOnProperty(name = "nova.outbox.transport", havingValue = "log")
class LoggingMessageTransport implements MessageTransport {

    static final String ALLOWED_PROPERTY = "nova.outbox.log-transport-allowed";

    private static final Logger log = LoggerFactory.getLogger(LoggingMessageTransport.class);

    LoggingMessageTransport(@Value("${" + ALLOWED_PROPERTY + ":false}") boolean allowed) {
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
