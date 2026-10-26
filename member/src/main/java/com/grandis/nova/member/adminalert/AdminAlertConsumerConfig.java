package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * 관리자 알림 큐 둘을 받아 각자의 Receiver 에 넘긴다(nova.sqs.consumer.enabled=true 일 때, 회원 알림 소비기와 같은 스위치).
 * 받기 · 지우기 · 늦춰 다시 받기는 common:sqs 가 맡는다. 받은 메시지 속성을 모두 받아 DLQ 로 옮길 때 그대로 싣는다(Grafana 큐는 속성이 없다).
 * Grafana 큐는 SQS 가 받은 시각(SentTimestamp)을 값의 시각으로 넘긴다.
 *
 * 소비기가 늘어 종료가 ECS 기본 stopTimeout 보다 길다 — member 작업 정의의 stop_timeout 이 필요하다(contracts/admin-alert-hub.md "종료").
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminAlertConsumerProperties.class)
class AdminAlertConsumerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "nova.sqs.consumer", name = "enabled", havingValue = "true")
    RetryingQueueConsumer adminAlertEventConsumer(SqsClient sqs, SqsQueueUrls queueUrls, AdminAlertEventReceiver receiver,
                                                  AdminAlertConsumerProperties properties, MeterRegistry meterRegistry) {
        return consumer(sqs, queueUrls, properties, properties.eventQueue(), message -> receiver.receive(message.body()), meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.sqs.consumer", name = "enabled", havingValue = "true")
    RetryingQueueConsumer adminAlertGrafanaConsumer(SqsClient sqs, SqsQueueUrls queueUrls, GrafanaAlertReceiver receiver,
                                                    AdminAlertConsumerProperties properties, MeterRegistry meterRegistry) {
        return consumer(sqs, queueUrls, properties, properties.grafanaQueue(),
                message -> receiver.receive(message.body(), sentAt(message)), meterRegistry);
    }

    private static RetryingQueueConsumer consumer(SqsClient sqs, SqsQueueUrls queueUrls, AdminAlertConsumerProperties properties,
                                                  String queue, Consumer<Message> receiver, MeterRegistry meterRegistry) {
        AdminAlertDeadLetters deadLetters = new AdminAlertDeadLetters(sqs, queueUrls, queue);
        return new RetryingQueueConsumer(sqs, queueUrls, properties.toSettings(queue), List.of("All"),
                new AdminAlertQueueHandler(queue, receiver, deadLetters, meterRegistry));
    }

    /** SQS 가 메시지를 받은 시각. common:sqs 가 SentTimestamp 를 받아 온다. 없거나 숫자가 아니면 null(Receiver 가 지금 시각을 쓴다). */
    static Instant sentAt(Message message) {
        String millis = message.attributes().get(MessageSystemAttributeName.SENT_TIMESTAMP);
        if (millis == null) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(millis));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
