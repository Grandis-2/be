package com.grandis.nova.order.event;

import com.grandis.nova.common.sqs.DeferredRedelivery;
import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Clock;

/**
 * order-events 큐를 받아 분배기에 넘긴다(nova.sqs.consumer.enabled=true 일 때). 처리하면 지우고, 실패하면 늦춰 다시 받다가
 * 재수신 한도를 넘으면 큐의 재드라이브 정책이 DLQ 로 옮긴다. 받기 · 지우기 · 다시 보이기는 common:sqs 가 맡는다.
 * 지금 결과를 정할 수 없는 메시지는 같은 큐에 지연을 두고 다시 보낸 뒤 지운다({@link DeferredRedelivery}). 포기하지 않는 까닭 —
 * preorder 에 정리 결과를 돌려줄 재료는 이 메시지 하나뿐이라(order 는 취소 시도 순번을 저장하지 않는다), 버리면 환불이 늦게 끝나거나
 * 사람이 해소해도 예약이 취소 중에 남는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderEventConsumerProperties.class)
class OrderEventConsumerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "nova.sqs.consumer", name = "enabled", havingValue = "true")
    RetryingQueueConsumer orderEventConsumer(SqsClient sqs, SqsQueueUrls queueUrls, OrderEventDispatcher dispatcher,
                                             OrderEventConsumerProperties properties, Clock clock) {
        DeferredRedelivery redelivery = new DeferredRedelivery(sqs, queueUrls, properties.queue(),
                properties.toDeferSettings(), clock);
        return new RetryingQueueConsumer(sqs, queueUrls, properties.toSettings(), DeferredRedelivery.ATTRIBUTES,
                redelivery.handler(message -> dispatcher.dispatch(message.body())));
    }
}
