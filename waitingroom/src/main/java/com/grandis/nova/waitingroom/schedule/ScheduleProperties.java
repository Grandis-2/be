package com.grandis.nova.waitingroom.schedule;

import com.grandis.nova.common.sqs.QueuePollerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 회차 일정 수신 · 재발행 요청 설정. 기본값은 코드에 둔다. 큐 주소 · 지역은 nova.sqs 가 정한다.
 *
 * @param queue       일정 이벤트를 받는 큐
 * @param resyncQueue 재발행 요청을 보낼 preorder 의 소비 큐
 */
@ConfigurationProperties("waitingroom.schedule")
public record ScheduleProperties(String queue, String resyncQueue, Integer concurrency, Integer waitSeconds,
                                 Integer maxMessages, Duration visibility, Duration backoffBase,
                                 Duration backoffMax) {

    public ScheduleProperties {
        queue = queue == null ? "waitingroom-events" : queue;
        resyncQueue = resyncQueue == null ? "preorder-events" : resyncQueue;
        // 일정 이벤트는 회차가 바뀔 때만 온다. 한 소비자로 충분하고, 순서는 일정 번호가 맞춘다
        concurrency = concurrency == null ? 1 : concurrency;
        waitSeconds = waitSeconds == null ? 20 : waitSeconds;
        maxMessages = maxMessages == null ? 10 : maxMessages;
        visibility = visibility == null ? Duration.ofSeconds(30) : visibility;
        backoffBase = backoffBase == null ? Duration.ofSeconds(1) : backoffBase;
        backoffMax = backoffMax == null ? Duration.ofMinutes(5) : backoffMax;
    }

    QueuePollerSettings poller() {
        return new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }
}
