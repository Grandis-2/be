package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.QueuePollerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 소비 큐의 DLQ 를 DB 로 옮기는 소비기 설정. 범위 검증은 {@link QueuePollerSettings} 가 바인딩할 때 한다.
 * 작업 하나가 한 번에 최대 10건씩 받는다. 적재가 실패하면 늦추지 않고 가시성 시간 뒤 다시 받으므로 재시도 대기 칸이 없다 —
 * 넘기는 대기 값은 검증만 통과시킬 뿐 쓰이지 않는다.
 *
 * @param queue      DLQ 이름. 소비 큐와 짝이다
 * @param visibility 받은 메시지를 숨기는 시간. 적재가 실패하면 이 시간 뒤 다시 받는다
 */
@ConfigurationProperties("nova.sqs.dead-letter")
record DeadLetterConsumerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("preorder-events-dlq") String queue,
        @DefaultValue("20") int waitSeconds,
        @DefaultValue("1m") Duration visibility
) {

    private static final int CONCURRENCY = 1;
    private static final int MAX_MESSAGES = 10;
    private static final Duration UNUSED_BACKOFF = Duration.ofSeconds(1);

    DeadLetterConsumerProperties {
        settings(queue, waitSeconds, visibility);
    }

    QueuePollerSettings toSettings() {
        return settings(queue, waitSeconds, visibility);
    }

    private static QueuePollerSettings settings(String queue, int waitSeconds, Duration visibility) {
        return new QueuePollerSettings(queue, CONCURRENCY, waitSeconds, MAX_MESSAGES, visibility,
                UNUSED_BACKOFF, UNUSED_BACKOFF);
    }
}
