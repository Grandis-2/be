package com.grandis.nova.common.sqs;

import software.amazon.awssdk.services.sqs.model.Message;

/**
 * {@link BatchQueueConsumer} 가 받은 메시지 하나를 처리하는 서비스 쪽 함수. 여러 메시지가 동시에 불린다.
 * 던지면 {@link MessageOutcome#keep()} 과 같다 — 가시성 시간 뒤 다시 받는다. 같은 메시지를 두 번 받아도 되게 만든다.
 */
@FunctionalInterface
public interface BatchMessageHandler {

    MessageOutcome handle(Message message);
}
