package com.grandis.nova.payment.event;

import com.grandis.nova.common.sqs.QueuePollerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * payment-events 소비기 설정. 범위 검증은 {@link QueuePollerSettings} 가 하고, 꺼 둔 환경에서도 바인딩할 때 한다 —
 * 잘못된 값이 조용히 남았다가 켜는 날 처음 드러나지 않게.
 *
 * @param visibility  받은 메시지를 다른 소비자에게 숨기는 시간. 처리 한 건보다 길게 둔다
 * @param backoffBase 처리 실패 후 다시 보이기까지의 첫 대기. 실패할수록 두 배, backoffMax 까지. 실패는 대개 DB 장애라 장애 조치
 *                    (1~2분)보다 길게 버티도록 order 소비기보다 길게 둔다 — 재수신 5회면 약 7분
 */
@ConfigurationProperties("nova.sqs.consumer")
record PaymentEventConsumerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("payment-events") String queue,
        @DefaultValue("1") int concurrency,
        @DefaultValue("20") int waitSeconds,
        @DefaultValue("10") int maxMessages,
        @DefaultValue("5m") Duration visibility,
        @DefaultValue("30s") Duration backoffBase,
        @DefaultValue("5m") Duration backoffMax
) {

    PaymentEventConsumerProperties {
        new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }

    QueuePollerSettings toSettings() {
        return new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }
}
