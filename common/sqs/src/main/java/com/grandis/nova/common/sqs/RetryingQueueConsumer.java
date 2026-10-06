package com.grandis.nova.common.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.util.List;

/**
 * 받은 메시지를 서비스의 처리 함수에 넘긴다. 처리하면 지우고, 실패하면 지우지 않고 다시 보일 때까지 늦춘다
 * (지수 백오프 + 지터). 계속 실패하면 큐의 재드라이브 정책이 DLQ 로 옮긴다.
 *
 * 처리 중 Error(독이 든 메시지가 일으킨 스택 넘침 · AssertionError · 링크 오류 등)도 그 메시지의 실패로 처리한다 —
 * 그 한 건 때문에 작업 스레드가 끝나면 큐 전체가 멈춘다. 메모리 부족만 다시 던진다(이어 처리해도 또 실패한다).
 */
public final class RetryingQueueConsumer extends QueuePoller {

    private static final Logger log = LoggerFactory.getLogger(RetryingQueueConsumer.class);

    private final QueueMessageHandler handler;
    /**
     * 처리에 실패한 메시지를 다시 보이게 할 때까지의 대기. 받은 횟수마다 두 배, 상한까지(절반은 무작위).
     * SDK 는 1번째 시도의 대기를 0 으로 두므로 받은 횟수에 1 을 더해 넘긴다 — 첫 실패부터 기본 대기를 둔다.
     */
    private final BackoffStrategy retryBackoff;

    public RetryingQueueConsumer(SqsClient sqs, SqsQueueUrls queueUrls, QueuePollerSettings settings,
                                 QueueMessageHandler handler) {
        this(sqs, queueUrls, settings, List.of(), handler);
    }

    /** @param messageAttributeNames 처리 함수가 읽을 메시지 속성 이름. 적지 않은 속성은 받은 메시지에 없다 */
    public RetryingQueueConsumer(SqsClient sqs, SqsQueueUrls queueUrls, QueuePollerSettings settings,
                                 List<String> messageAttributeNames, QueueMessageHandler handler) {
        super(sqs, queueUrls, settings, messageAttributeNames);
        this.handler = handler;
        this.retryBackoff = BackoffStrategy.exponentialDelayHalfJitter(settings.backoffBase(), settings.backoffMax());
    }

    @Override
    protected void handle(String queueUrl, Message message) {
        try {
            handler.handle(message);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception | Error e) {
            retryLater(queueUrl, message, e);
            return;
        }
        try {
            sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException e) {
            log.warn("처리한 메시지를 지우지 못했다 — 다시 받으면 멱등하게 한 번 더 처리된다 queue={} messageId={}",
                    settings.queue(), message.messageId(), e);
        }
    }

    private void retryLater(String queueUrl, Message message, Throwable cause) {
        int receiveCount = receiveCount(message);
        Duration delay = retryBackoff.computeDelay(receiveCount + 1);
        log.warn("메시지 처리 실패 — {} 뒤 다시 받는다 queue={} messageId={} receiveCount={}",
                delay, settings.queue(), message.messageId(), receiveCount, cause);
        try {
            sqs.changeMessageVisibility(request -> request.queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .visibilityTimeout(seconds(delay)));
        } catch (RuntimeException e) {
            log.warn("다시 받을 시각을 늦추지 못했다 — 가시성 시간이 지나면 다시 보인다 queue={} messageId={}",
                    settings.queue(), message.messageId(), e);
        }
    }

    /** SQS 는 초 단위다. 1초 미만은 올려 곧바로 다시 보이지 않게 한다. */
    private static int seconds(Duration delay) {
        return (int) Math.max(1, (delay.toMillis() + 999) / 1000);
    }
}
