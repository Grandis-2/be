package com.grandis.nova.catalog.event;

import com.grandis.nova.common.sqs.QueuePollerSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * catalog-events 소비기 설정. 기본은 꺼짐이다 — 큐(인프라)와 보내는 쪽이 준비된 뒤 설정으로 켠다.
 * 범위 검증은 {@link QueuePollerSettings} 가 하고, 꺼 둔 환경에서도 바인딩할 때 한다 — 잘못된 값이 조용히 남았다가 켜는 날 처음 드러나지 않게.
 *
 * @param visibility  받은 메시지를 다른 소비자에게 숨기는 시간. 처리 한 건보다 길게 둔다
 * @param backoffBase 처리 실패 후 다시 보이기까지의 첫 대기. 실패할수록 두 배, backoffMax 까지. 실패는 대개 DB 장애라
 *                    장애 조치(1~2분)보다 길게 버티도록 둔다(order 와 같은 값)
 */
@ConfigurationProperties("nova.sqs.consumer")
record CatalogEventConsumerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("catalog-events") String queue,
        @DefaultValue("1") int concurrency,
        @DefaultValue("20") int waitSeconds,
        @DefaultValue("10") int maxMessages,
        @DefaultValue("5m") Duration visibility,
        @DefaultValue("30s") Duration backoffBase,
        @DefaultValue("5m") Duration backoffMax
) {

    CatalogEventConsumerProperties {
        new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }

    QueuePollerSettings toSettings() {
        return new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }
}
