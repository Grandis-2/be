package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.preorder.deadletter.DeadLetterRedriver;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.Optional;
import java.util.UUID;

/**
 * 되돌린 메시지에 실린 DLQ 행 id({@link DeadLetterRedriver#DEAD_LETTER_ID_ATTRIBUTE}). 소비기가 그 속성을 받도록
 * 적어야 메시지에 실린다 — 적지 않으면 늘 비어 있다.
 */
final class DeadLetterIds {

    private DeadLetterIds() {
    }

    /** 없거나 읽을 수 없으면 비어 있다. */
    static Optional<UUID> of(Message message) {
        return Optional.ofNullable(message.messageAttributes().get(DeadLetterRedriver.DEAD_LETTER_ID_ATTRIBUTE))
                .map(MessageAttributeValue::stringValue)
                .flatMap(value -> {
                    try {
                        return Optional.of(UUID.fromString(value));
                    } catch (IllegalArgumentException e) {
                        return Optional.empty();
                    }
                });
    }
}
