package com.grandis.nova.preorder.outbox;

/**
 * preorder 가 발행하는 메시지. 메시지는 발행하는 모듈이 이 인터페이스를 구현한 record 로 정의한다.
 *
 * record 의 칸이 곧 payload 다. 이벤트 종류 · aggregate 를 record 가 함께 정하므로
 * 종류와 본문이 어긋난 메시지를 만들 수 없다. 소비자는 payload 를 믿지 않고 aggregateId 로 원장을 다시 읽는다 —
 * 그래서 payload 에는 식별자와 최소 정보만 담고 민감정보는 넣지 않는다.
 */
public interface OutboxMessage {

    OutboundEventType eventType();

    AggregateType aggregateType();

    Long aggregateId();
}
