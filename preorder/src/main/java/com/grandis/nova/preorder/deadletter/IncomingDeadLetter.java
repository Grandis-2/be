package com.grandis.nova.preorder.deadletter;

import java.time.Instant;

/**
 * DLQ 에서 받은 메시지 하나.
 *
 * @param sourceQueue    원래 큐(논리 이름). 되돌릴 곳이다
 * @param redrivenFromId 되돌렸던 메시지면 그때의 행 id(메시지 속성). 아니면 null
 */
public record IncomingDeadLetter(
        String sourceQueue,
        String messageId,
        String body,
        int receiveCount,
        Instant sentAt,
        Long redrivenFromId
) {

    /** 앞선 행을 잇지 않는다(속성이 가리키는 행이 없을 때). */
    IncomingDeadLetter withoutPrevious() {
        return new IncomingDeadLetter(sourceQueue, messageId, body, receiveCount, sentAt, null);
    }
}
