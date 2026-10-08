package com.grandis.nova.preorder.deadletter;

import java.util.UUID;

/** 원문을 원래 큐로 다시 보낸다. 구현은 큐 연동(integration.sqs)에 있다. */
public interface DeadLetterRedriver {

    /** 되돌린 메시지에 싣는 속성. 처리 결과를 이 행에 남길 때 쓴다. */
    String DEAD_LETTER_ID_ATTRIBUTE = "deadLetterId";

    /** @throws RuntimeException 보내지 못했다 */
    void redrive(String sourceQueue, String body, UUID deadLetterId);
}
