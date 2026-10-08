package com.grandis.nova.order.event;

import com.grandis.nova.common.sqs.DeferredRedelivery;
import com.grandis.nova.common.sqs.QueuePollerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * order-events 소비기 설정. 범위 검증은 {@link QueuePollerSettings} 가 하고, 꺼 둔 환경에서도 바인딩할 때 한다 —
 * 잘못된 값이 조용히 남았다가 켜는 날 처음 드러나지 않게.
 *
 * @param visibility       받은 메시지를 다른 소비자에게 숨기는 시간. 처리 한 건보다 길게 둔다
 * @param backoffBase      처리 실패 후 다시 보이기까지의 첫 대기. 실패할수록 두 배, backoffMax 까지
 * @param deferFirstDelay  결과를 정할 수 없는 메시지를 다시 보낼 때의 첫 지연. 보류할 때마다 두 배, deferMaxDelay 까지(에픽 U7)
 * @param deferAlertAfter  처음 보류한 뒤 이만큼 지나면 ERROR 를 한 번 남긴다(계속 늦춰 다시 받는다)
 */
@ConfigurationProperties("nova.sqs.consumer")
record OrderEventConsumerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("order-events") String queue,
        @DefaultValue("1") int concurrency,
        @DefaultValue("20") int waitSeconds,
        @DefaultValue("10") int maxMessages,
        @DefaultValue("5m") Duration visibility,
        @DefaultValue("5s") Duration backoffBase,
        @DefaultValue("5m") Duration backoffMax,
        @DefaultValue("60s") Duration deferFirstDelay,
        @DefaultValue("15m") Duration deferMaxDelay,
        @DefaultValue("24h") Duration deferAlertAfter
) {

    OrderEventConsumerProperties {
        new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
        new DeferredRedelivery.Settings(deferFirstDelay, deferMaxDelay, deferAlertAfter);
    }

    QueuePollerSettings toSettings() {
        return new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }

    DeferredRedelivery.Settings toDeferSettings() {
        return new DeferredRedelivery.Settings(deferFirstDelay, deferMaxDelay, deferAlertAfter);
    }
}
