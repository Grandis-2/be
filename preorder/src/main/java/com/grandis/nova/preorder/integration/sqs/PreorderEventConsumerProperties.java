package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.DeferredRedelivery;
import com.grandis.nova.common.sqs.QueuePollerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * preorder-events 소비기 설정. 범위 검증은 {@link QueuePollerSettings} · {@link DeferredRedelivery.Settings} 가 하고,
 * 꺼 둔 환경에서도 바인딩할 때 한다 — 잘못된 값이 조용히 남았다가 켜는 날 처음 드러나지 않게.
 *
 * @param visibility      받은 메시지를 다른 소비자에게 숨기는 시간. 처리 한 건(회차 취소 포함)보다 길게 둔다
 * @param backoffBase     처리 실패 후 다시 보이기까지의 첫 대기. 실패할수록 두 배, backoffMax 까지
 * @param deferFirstDelay 앞선 이벤트를 기다리는 메시지를 다시 보낼 때의 첫 지연. 보류할 때마다 두 배, deferMaxDelay 까지.
 *                        순서가 뒤집히는 시간은 보통 짧아 짧게 둔다
 * @param deferMaxDelay   보류 지연의 상한. SQS DelaySeconds 상한(15분) 이하
 * @param deferAlertAfter 처음 보류한 뒤 이만큼 지나면 ERROR 를 한 번 남긴다(계속 늦춰 다시 받는다)
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
        @DefaultValue("5m") Duration backoffMax,
        @DefaultValue("10s") Duration deferFirstDelay,
        @DefaultValue("15m") Duration deferMaxDelay,
        @DefaultValue("1h") Duration deferAlertAfter
) {

    PreorderEventConsumerProperties {
        new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
        new DeferredRedelivery.Settings(deferFirstDelay, deferMaxDelay, deferAlertAfter);
    }

    DeferredRedelivery.Settings toDeferSettings() {
        return new DeferredRedelivery.Settings(deferFirstDelay, deferMaxDelay, deferAlertAfter);
    }

    QueuePollerSettings toSettings() {
        return new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }
}
