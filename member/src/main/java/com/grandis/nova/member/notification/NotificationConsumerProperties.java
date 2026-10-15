package com.grandis.nova.member.notification;

import com.grandis.nova.common.sqs.QueuePollerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * notification 소비기 설정. 키는 order 와 같다(nova.sqs.consumer.*) — 인프라가 서비스마다 같은 이름으로 넣는다.
 * 범위 검증은 {@link QueuePollerSettings} 가 하고, 꺼 둔 환경에서도 바인딩할 때 한다 — 잘못된 값이 켜는 날 처음 드러나지 않게.
 *
 * @param visibility  받은 메시지를 다른 소비자에게 숨기는 시간. 처리 한 건보다 길게 둔다
 * @param backoffBase 처리 실패 후 다시 보이기까지의 첫 대기. 실패할수록 두 배, backoffMax 까지
 */
@ConfigurationProperties("nova.sqs.consumer")
record NotificationConsumerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("notification") String queue,
        @DefaultValue("1") int concurrency,
        @DefaultValue("20") int waitSeconds,
        @DefaultValue("10") int maxMessages,
        @DefaultValue("5m") Duration visibility,
        @DefaultValue("5s") Duration backoffBase,
        @DefaultValue("5m") Duration backoffMax
) {

    NotificationConsumerProperties {
        new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }

    QueuePollerSettings toSettings() {
        return new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }
}
