package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.sqs.QueuePollerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 관리자 알림 소비기 둘(admin-alert · admin-alert-grafana)의 설정. 기본값이 운영값이라 인프라에 적지 않아도 된다. 켜고 끄는 것은 회원 알림과 같은
 * nova.sqs.consumer.enabled 하나다. 범위 검증은 {@link QueuePollerSettings} 가 바인딩할 때 한다.
 *
 * @param eventQueue   preorder · order 의 ADMIN_ALERT_REQUESTED 큐
 * @param grafanaQueue Grafana 경보 큐(SNS 원문 전달)
 * @param maxMessages  한 번에 받는 수. 받은 묶음은 차례로 처리한다
 * @param visibility   받은 메시지를 다른 소비자에게 숨기는 시간. 한 묶음을 다 처리하는 시간보다 길게 둔다 — DB 가 끊기면 건마다 연결 대기
 *                     (Hikari 30초)가 걸려 5건 × 30초 = 2분 30초다
 * @param backoffBase  처리 실패 후 다시 보이기까지의 첫 대기. 실패할수록 두 배, backoffMax 까지
 */
@ConfigurationProperties("member.admin-alert.consumer")
record AdminAlertConsumerProperties(
        @DefaultValue("admin-alert") String eventQueue,
        @DefaultValue("admin-alert-grafana") String grafanaQueue,
        @DefaultValue("1") int concurrency,
        @DefaultValue("20") int waitSeconds,
        @DefaultValue("5") int maxMessages,
        @DefaultValue("3m") Duration visibility,
        @DefaultValue("30s") Duration backoffBase,
        @DefaultValue("5m") Duration backoffMax
) {

    AdminAlertConsumerProperties {
        settings(eventQueue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
        settings(grafanaQueue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }

    QueuePollerSettings toSettings(String queue) {
        return settings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }

    private static QueuePollerSettings settings(String queue, int concurrency, int waitSeconds, int maxMessages, Duration visibility,
                                                Duration backoffBase, Duration backoffMax) {
        return new QueuePollerSettings(queue, concurrency, waitSeconds, maxMessages, visibility, backoffBase, backoffMax);
    }
}
