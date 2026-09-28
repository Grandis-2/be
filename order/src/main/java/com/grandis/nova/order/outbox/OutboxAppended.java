package com.grandis.nova.order.outbox;

/**
 * 아웃박스에 메시지를 적었다는 이벤트. 발행기가 커밋 직후(AFTER_COMMIT) 받아 보낸다.
 * 롤백되면 전달되지 않는다. 커밋 직후 죽어 사라지면 릴레이가 다시 보낸다.
 *
 * 받는 쪽은 {@link OutboxAfterCommitPublisher} 다.
 *
 * common:outbox 이전 시: preorder 에 같은 record 가 있다. 그대로 공통으로 옮긴다.
 */
public record OutboxAppended(Long outboxEventId) {
}
