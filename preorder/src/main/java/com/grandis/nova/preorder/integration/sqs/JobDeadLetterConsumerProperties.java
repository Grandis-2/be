package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.QueuePollerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * worker 로 보낸 작업 메시지(등록 · 취소)의 DLQ 를 받는 소비기 설정. 큐마다 작업 하나가 한 번에 최대 10건씩 받는다.
 * 반영이 실패하면 늦추지 않고 가시성 시간 뒤 다시 받으므로 재시도 대기 칸이 없다 — 넘기는 대기 값은 검증만 통과시킨다.
 *
 * @param visibility 받은 메시지를 숨기는 시간. 반영이 실패하면 이 시간 뒤 다시 받는다
 */
@ConfigurationProperties("nova.sqs.job-dead-letter")
record JobDeadLetterConsumerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("20") int waitSeconds,
        @DefaultValue("1m") Duration visibility
) {

    private static final int CONCURRENCY = 1;
    private static final int MAX_MESSAGES = 10;
    private static final Duration UNUSED_BACKOFF = Duration.ofSeconds(1);

    JobDeadLetterConsumerProperties {
        settings("queue", waitSeconds, visibility);
    }

    QueuePollerSettings toSettings(String queue) {
        return settings(queue, waitSeconds, visibility);
    }

    private static QueuePollerSettings settings(String queue, int waitSeconds, Duration visibility) {
        return new QueuePollerSettings(queue, CONCURRENCY, waitSeconds, MAX_MESSAGES, visibility,
                UNUSED_BACKOFF, UNUSED_BACKOFF);
    }
}
