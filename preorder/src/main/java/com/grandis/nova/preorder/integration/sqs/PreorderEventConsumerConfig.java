package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.DeferredRedelivery;
import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.preorder.deadletter.DeadLetterRedriver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

/**
 * preorder-events 큐를 받아 처리 함수에 넘긴다(nova.sqs.consumer.enabled=true 일 때). 처리하면 지우고, 실패하면 늦춰 다시 받다가
 * 재수신 한도를 넘으면 큐의 재드라이브 정책이 DLQ 로 옮긴다. 받기 · 지우기 · 다시 보이기는 common:sqs 가 맡는다.
 * 되돌리기 표식(deadLetterId) 속성을 함께 받아야 처리 결과를 DLQ 행에 남길 수 있다.
 * 앞선 이벤트를 기다리는 메시지(DEFER)는 같은 큐에 지연을 두고 다시 보낸 뒤 지운다 — 재수신 횟수를 쓰지 않아 DLQ 로 새지 않는다.
 *
 * 설정 레코드 둘은 소비기를 꺼 둔 환경에서도 바인딩해 검증한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PreorderEventConsumerProperties.class, DeadLetterConsumerProperties.class})
class PreorderEventConsumerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "nova.sqs.consumer", name = "enabled", havingValue = "true")
    RetryingQueueConsumer preorderEventConsumer(SqsClient sqs, SqsQueueUrls queueUrls,
                                                PreorderEventMessageHandler handler,
                                                PreorderEventConsumerProperties properties, Clock clock) {
        DeferredRedelivery redelivery = new DeferredRedelivery(sqs, queueUrls, properties.queue(),
                properties.toDeferSettings(), clock);
        List<String> attributes = Stream.concat(DeferredRedelivery.ATTRIBUTES.stream(),
                Stream.of(DeadLetterRedriver.DEAD_LETTER_ID_ATTRIBUTE)).toList();
        return new RetryingQueueConsumer(sqs, queueUrls, properties.toSettings(), attributes,
                redelivery.handler(handler::handle));
    }
}
