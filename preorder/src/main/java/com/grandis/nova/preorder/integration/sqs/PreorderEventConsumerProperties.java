package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.QueuePollerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * preorder-events 소비기 설정. 범위 검증은 {@link QueuePollerSettings} 가 하고, 꺼 둔 환경에서도 바인딩할 때 한다 —
 * 잘못된 값이 조용히 남았다가 켜는 날 처음 드러나지 않게.
 *
 * @param visibility  받은 메시지를 다른 소비자에게 숨기는 시간. 처리 한 건(회차 취소 포함)보다 길게 둔다
 * @param backoffBase 처리 실패 후 다시 보이기까지의 첫 대기. 실패할수록 두 배, backoffMax 까지
 */
@ConfigurationProperties("nova.sqs.consumer")
record PreorderEventConsumerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("preorder-events") String queue,
        @DefaultValue("1") int concurrency,
        @DefaultValue("20") int waitSeconds,
        @DefaultValue("10") int maxMessages,
        @DefaultValue("5m") Duration visibility,
        @DefaultValue("5s") Duration backoffBase,
        @DefaultValue("5m") Duration backoffMax
) {

    PreorderEventConsumerProperties {
        new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }

    QueuePollerSettings toSettings() {
        return new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }
}
