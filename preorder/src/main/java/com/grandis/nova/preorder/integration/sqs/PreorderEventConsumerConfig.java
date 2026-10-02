package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.preorder.deadletter.DeadLetterRedriver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.List;

/**
 * preorder-events 큐를 받아 처리 함수에 넘긴다(nova.sqs.consumer.enabled=true 일 때). 처리하면 지우고, 실패하면 늦춰 다시 받다가
 * 재수신 한도를 넘으면 큐의 재드라이브 정책이 DLQ 로 옮긴다. 받기 · 지우기 · 다시 보이기는 common:sqs 가 맡는다.
 * 되돌리기 표식(deadLetterId) 속성을 함께 받아야 처리 결과를 DLQ 행에 남길 수 있다.
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
                                                PreorderEventConsumerProperties properties) {
        return new RetryingQueueConsumer(sqs, queueUrls, properties.toSettings(),
                List.of(DeadLetterRedriver.DEAD_LETTER_ID_ATTRIBUTE), handler);
    }
}
