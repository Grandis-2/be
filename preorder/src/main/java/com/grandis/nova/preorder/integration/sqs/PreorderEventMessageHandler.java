package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.QueueMessageHandler;
import com.grandis.nova.preorder.deadletter.DeadLetters;
import com.grandis.nova.preorder.event.PreorderEventDispatcher;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * preorder-events 메시지 하나를 분배기에 넘긴다. DLQ 에서 되돌린 메시지(deadLetterId 속성)면 처리 결과를 그 DLQ 행에 남긴다.
 * 결과를 남기지 못하면 던진다 — 소비기가 지우지 않고 다시 받는다(처리기는 같은 메시지를 두 번 받아도 된다).
 */
@Component
class PreorderEventMessageHandler implements QueueMessageHandler {

    private final PreorderEventDispatcher dispatcher;
    private final DeadLetters deadLetters;

    PreorderEventMessageHandler(PreorderEventDispatcher dispatcher, DeadLetters deadLetters) {
        this.dispatcher = dispatcher;
        this.deadLetters = deadLetters;
    }

    @Override
    public void handle(Message message) {
        dispatcher.dispatch(message.body());
        DeadLetterIds.of(message).ifPresent(deadLetters::markRedriveSucceeded);
    }
}
