package com.grandis.nova.catalog.outbox;

/**
 * catalog 가 발행하는 메시지. 발행하는 쪽이 이 인터페이스를 구현한 record 로 정의하고, record 의 칸이 곧 payload 다.
 * 이벤트 종류 · 대상을 record 가 함께 정하므로 종류와 본문이 어긋난 메시지를 만들 수 없다.
 * 대상 id 는 봉투의 aggregateId 로만 싣는다 — payload 에 다시 넣지 않는다(둘이 어긋날 수 있다). record 의 id 칸에 @JsonIgnore 를 붙인다.
 * 인터페이스 메서드(eventType · aggregateType · aggregateId)는 payload 에 들어가지 않는다(실측: record 칸만 직렬화된다).
 */
public interface OutboxMessage {

    OutboundEventType eventType();

    AggregateType aggregateType();

    Long aggregateId();
}
