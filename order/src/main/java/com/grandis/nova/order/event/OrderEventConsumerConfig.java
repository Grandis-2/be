package com.grandis.nova.order.event;

import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * order-events 큐를 받아 분배기에 넘긴다(nova.sqs.consumer.enabled=true 일 때). 처리하면 지우고, 실패하면 늦춰 다시 받다가
 * 재수신 한도를 넘으면 큐의 재드라이브 정책이 DLQ 로 옮긴다. 받기 · 지우기 · 다시 보이기는 common:sqs 가 맡는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderEventConsumerProperties.class)
class OrderEventConsumerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "nova.sqs.consumer", name = "enabled", havingValue = "true")
    RetryingQueueConsumer orderEventConsumer(SqsClient sqs, SqsQueueUrls queueUrls, OrderEventDispatcher dispatcher,
                                             OrderEventConsumerProperties properties) {
        return new RetryingQueueConsumer(sqs, queueUrls, properties.toSettings(),
                message -> dispatcher.dispatch(message.body()));
    }
}
