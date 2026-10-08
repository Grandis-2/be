package com.grandis.nova.common.outbox;

import java.util.UUID;

/**
 * 아웃박스에 적는 메시지. 서비스는 이 인터페이스를 잇는 자기 sealed 인터페이스와 record 로 메시지를 정의한다.
 *
 * record 의 칸이 곧 payload 다. 이 인터페이스의 메서드는 record 칸이 아니므로 payload 에 실리지 않는다.
 * 소비자는 payload 를 믿지 않고 aggregateId 로 원장을 다시 읽는다 — payload 에는 식별자와 최소 정보만 담고 민감정보는 넣지 않는다.
 */
public interface OutboxMessage {

    OutboxEventType eventType();

    OutboxAggregateType aggregateType();

    UUID aggregateId();
}
