package com.grandis.nova.order.outbox;

/**
 * order 가 발행하는 메시지.
 *
 * record 의 칸이 곧 payload 다. 이벤트 종류 · aggregate 를 record 가 함께 정하므로
 * 종류와 본문이 어긋난 메시지를 만들 수 없다. payload 에는 받는 쪽 계약에 있는 칸만 담고 민감정보는 넣지 않는다.
 *
 * common:outbox 이전 시: sealed 는 모듈을 넘지 못한다. 공통 쪽은 열린 인터페이스로 두고, 이 인터페이스는 그것을 잇는
 * order 전용 sealed 하위 인터페이스로 남긴다(preorder 도 같은 모양으로 맞춘다).
 */
public sealed interface OutboxMessage permits PreorderOrderSettled {

    OutboundEventType eventType();

    AggregateType aggregateType();

    Long aggregateId();
}
