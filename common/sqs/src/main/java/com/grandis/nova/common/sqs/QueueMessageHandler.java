package com.grandis.nova.common.sqs;

import software.amazon.awssdk.services.sqs.model.Message;

/**
 * 받은 메시지 하나를 처리하는 서비스 쪽 함수(보통 이벤트 분배기). 돌아오면 성공 — 메시지를 지운다.
 * 던지면 실패 — 지우지 않고 다시 받을 때까지 늦춘다. 같은 메시지를 두 번 받아도 되게 만든다.
 */
@FunctionalInterface
public interface QueueMessageHandler {

    void handle(Message message);
}
