package com.grandis.nova.common.outbox;

/**
 * 서비스가 발행하는 이벤트 종류와 논리 목적지. 서비스의 enum 이 구현한다 — enum 의 name() 이 그대로 이 계약을 채운다.
 * 목적지는 큐 이름이 아니라 논리 이름이다(실제 큐는 전송 구현이 푼다).
 */
public interface OutboxEventType {

    /** 표의 event_type 에 그대로 들어간다(50자 이하). */
    String name();

    String destination();
}
