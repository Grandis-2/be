package com.grandis.nova.member.notification;

import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.List;

/**
 * notification 큐를 받아 {@link NotificationQueueHandler} 에 넘긴다(nova.sqs.consumer.enabled=true 일 때). 받기 · 지우기 · 늦춰 다시 받기는
 * common:sqs 가 맡는다. order 와 달리 지연 재전송이 없다 — 알림은 지금 저장하거나, 계약 위반으로 DLQ 에 넣거나, 일시 오류로 다시 받는다.
 * 받은 메시지 속성(eventType · eventId)을 모두 받아 DLQ 로 옮길 때 그대로 싣는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationConsumerProperties.class)
class NotificationConsumerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "nova.sqs.consumer", name = "enabled", havingValue = "true")
    RetryingQueueConsumer notificationConsumer(SqsClient sqs, SqsQueueUrls queueUrls, NotificationReceiver receiver,
                                               NotificationConsumerProperties properties, MeterRegistry meterRegistry) {
        NotificationDeadLetters deadLetters = new NotificationDeadLetters(sqs, queueUrls, properties.queue());
        return new RetryingQueueConsumer(sqs, queueUrls, properties.toSettings(), List.of("All"),
                new NotificationQueueHandler(receiver, deadLetters, meterRegistry));
    }
}
