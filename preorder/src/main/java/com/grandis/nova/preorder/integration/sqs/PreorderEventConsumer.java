package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.preorder.deadletter.DeadLetters;
import com.grandis.nova.preorder.event.PreorderEventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;

/**
 * preorder-events 큐를 분배기에 넘긴다. 처리하면 지우고, 실패하면 지우지 않고 다시 보일 때까지 늦춘다
 * (지수 백오프 + 지터). 계속 실패하면 큐의 재드라이브 정책이 DLQ 로 옮긴다. 처리기는 같은 메시지를 두 번 받아도 된다.
 * DLQ 에서 되돌린 메시지(deadLetterId 속성)를 처리하면 그 결과를 DLQ 행에 남긴다.
 */
@Component
@ConditionalOnProperty(prefix = "nova.sqs.consumer", name = "enabled", havingValue = "true")
class PreorderEventConsumer extends QueuePoller {

    private static final Logger log = LoggerFactory.getLogger(PreorderEventConsumer.class);

    private final PreorderEventDispatcher dispatcher;
    private final DeadLetters deadLetters;
    /**
     * 처리에 실패한 메시지를 다시 보이게 할 때까지의 대기. 받은 횟수마다 두 배, 상한까지(절반은 무작위).
     * SDK 는 첫 시도를 0 으로 세므로 실패 횟수에 1 을 더해 넘긴다 — 첫 실패부터 기본 대기를 둔다.
     */
    private final BackoffStrategy retryBackoff;

    PreorderEventConsumer(SqsClient sqs, SqsQueueUrls queueUrls, PreorderEventDispatcher dispatcher,
                          DeadLetters deadLetters, SqsProperties properties) {
        super(sqs, queueUrls, properties.consumer().queue(), properties.consumer().concurrency(),
                properties.consumer().waitSeconds(), properties.consumer().maxMessages(),
                properties.consumer().visibility());
        this.dispatcher = dispatcher;
        this.deadLetters = deadLetters;
        this.retryBackoff = BackoffStrategy.exponentialDelayHalfJitter(properties.consumer().backoffBase(),
                properties.consumer().backoffMax());
    }

    @Override
    protected void handle(String queueUrl, Message message) {
        try {
            dispatcher.dispatch(message.body());
            // 되돌린 메시지의 결과를 남기지 못하면 지우지 않고 다시 받는다 — 처리기는 같은 메시지를 두 번 받아도 된다
            deadLetterId(message).ifPresent(deadLetters::markRedriveSucceeded);
        } catch (RuntimeException e) {
            retryLater(queueUrl, message, e);
            return;
        }
        try {
            sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException e) {
            log.warn("처리한 이벤트를 지우지 못했다 — 다시 받으면 멱등하게 한 번 더 처리된다 messageId={}",
                    message.messageId(), e);
        }
    }

    private void retryLater(String queueUrl, Message message, RuntimeException cause) {
        int receiveCount = receiveCount(message);
        Duration delay = retryBackoff.computeDelay(receiveCount + 1);
        log.warn("이벤트 처리 실패 — {} 뒤 다시 받는다 messageId={} receiveCount={}",
                delay, message.messageId(), receiveCount, cause);
        try {
            sqs.changeMessageVisibility(request -> request.queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .visibilityTimeout(seconds(delay)));
        } catch (RuntimeException e) {
            log.warn("다시 받을 시각을 늦추지 못했다 — 가시성 시간이 지나면 다시 보인다 messageId={}",
                    message.messageId(), e);
        }
    }

    /** SQS 는 초 단위다. 1초 미만은 올려 곧바로 다시 보이지 않게 한다. */
    private int seconds(Duration delay) {
        return (int) Math.max(1, (delay.toMillis() + 999) / 1000);
    }
}
