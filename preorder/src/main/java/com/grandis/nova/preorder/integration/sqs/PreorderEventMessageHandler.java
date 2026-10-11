package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.MessageHandling;
import com.grandis.nova.preorder.deadletter.DeadLetters;
import com.grandis.nova.preorder.event.PreorderEventDispatcher;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * preorder-events 메시지 하나를 분배기에 넘긴다. DLQ 에서 되돌린 메시지(deadLetterId 속성)면 처리 결과를 그 DLQ 행에 남긴다.
 * 결과를 남기지 못하면 던진다 — 소비기가 지우지 않고 다시 받는다(처리기는 같은 메시지를 두 번 받아도 된다).
 * 보류(DEFER)는 아직 처리하지 않은 것이라 남기지 않는다 — 늦춰 보낸 사본이 같은 속성을 이어 받아 처리될 때 남긴다.
 */
@Component
class PreorderEventMessageHandler {

    private final PreorderEventDispatcher dispatcher;
    private final DeadLetters deadLetters;

    PreorderEventMessageHandler(PreorderEventDispatcher dispatcher, DeadLetters deadLetters) {
        this.dispatcher = dispatcher;
        this.deadLetters = deadLetters;
    }

    MessageHandling handle(Message message) {
        if (dispatcher.dispatch(message.body()) == MessageHandling.DEFER) {
            return MessageHandling.DEFER;
        }
        DeadLetterIds.of(message).ifPresent(deadLetters::markRedriveSucceeded);
        return MessageHandling.DONE;
    }
}
