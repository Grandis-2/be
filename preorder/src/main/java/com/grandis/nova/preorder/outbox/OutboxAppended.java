package com.grandis.nova.preorder.outbox;

/**
 * 아웃박스에 메시지를 적었다는 이벤트. 발행기가 커밋 직후 받아 보낸다.
 * 롤백되면 전달되지 않는다. 커밋 직후 죽어 사라지면 릴레이가 다시 보낸다.
 */
public record OutboxAppended(Long outboxEventId) {
}
