package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.preorder.deadletter.DeadLetters;
import com.grandis.nova.preorder.deadletter.IncomingDeadLetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.time.Instant;

/**
 * 소비 큐의 DLQ 를 받아 DB(dead_letter_events)로 옮긴다. 적재가 커밋된 뒤에만 DLQ 에서 지운다 —
 * 적재가 실패하면 가시성 시간 뒤 다시 받고, 지우기 전에 죽어 다시 받으면 이미 쌓인 메시지라 지우기만 한다.
 */
@Component
@ConditionalOnProperty(prefix = "nova.sqs.dead-letter", name = "enabled", havingValue = "true")
class DeadLetterConsumer extends QueuePoller {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);
    private static final int MAX_MESSAGES = 10;

    private final DeadLetters deadLetters;
    private final String sourceQueue;

    DeadLetterConsumer(SqsClient sqs, SqsQueueUrls queueUrls, DeadLetters deadLetters, SqsProperties properties) {
        super(sqs, queueUrls, properties.deadLetter().queue(), 1, properties.deadLetter().waitSeconds(),
                MAX_MESSAGES, properties.deadLetter().visibility());
        this.deadLetters = deadLetters;
        this.sourceQueue = properties.consumer().queue();
    }

    @Override
    protected void handle(String queueUrl, Message message) {
        try {
            if (deadLetters.record(toIncoming(message))) {
                log.warn("DLQ 메시지를 DB 로 옮겼다 messageId={}", message.messageId());
            }
        } catch (RuntimeException e) {
            log.warn("DLQ 메시지를 DB 로 옮기지 못했다 — 가시성 시간 뒤 다시 받는다 messageId={}", message.messageId(), e);
            return;
        }
        try {
            sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException e) {
            log.warn("DB 로 옮긴 DLQ 메시지를 지우지 못했다 — 다시 받으면 지우기만 한다 messageId={}",
                    message.messageId(), e);
        }
    }

    private IncomingDeadLetter toIncoming(Message message) {
        return new IncomingDeadLetter(sourceQueue, message.messageId(), message.body(), receiveCount(message),
                sentAt(message), deadLetterId(message).orElse(null));
    }

    /** SQS 가 처음 받은 시각(epoch 밀리초). 읽을 수 없으면 null. */
    private static Instant sentAt(Message message) {
        String value = message.attributes().get(MessageSystemAttributeName.SENT_TIMESTAMP);
        if (value == null) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
