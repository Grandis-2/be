package com.grandis.nova.preorder.support;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** 테스트에서 큐에 넣고 꺼내 보는 도우미. 큐를 테스트끼리 공유하므로 꺼낼 때는 조건에 맞는 것만 지운다. */
public class TestQueues {

    private final SqsClient sqs;

    public TestQueues(SqsClient sqs) {
        this.sqs = sqs;
    }

    public void send(String queue, String body) {
        sqs.sendMessage(request -> request.queueUrl(url(queue)).messageBody(body));
    }

    /** 조건에 맞는 메시지가 올 때까지 받는다. 맞는 것은 지우고, 다른 테스트의 메시지는 그대로 둔다. */
    public Optional<Message> receive(String queue, Predicate<Message> match, Duration timeout) {
        String url = url(queue);
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (Message message : sqs.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(1)
                    .maxNumberOfMessages(10).visibilityTimeout(1)
                    .messageAttributeNames("All")
                    .messageSystemAttributeNames(MessageSystemAttributeName.ALL)).messages()) {
                if (match.test(message)) {
                    sqs.deleteMessage(request -> request.queueUrl(url).receiptHandle(message.receiptHandle()));
                    return Optional.of(message);
                }
            }
        }
        return Optional.empty();
    }

    /** 대기 중 + 받았지만 아직 지우지 않은 메시지 수. */
    public int messagesIn(String queue) {
        Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(request -> request
                .queueUrl(url(queue))
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
        return Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                + Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }

    private String url(String queue) {
        return sqs.getQueueUrl(request -> request.queueName(queue)).queueUrl();
    }
}
