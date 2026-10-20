package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.preorder.event.PreorderEventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 작업 큐 DLQ 소비기(nova.sqs.job-dead-letter.enabled=true 일 때). 큐마다 하나다 — 스레드 이름이 큐 이름이라 어느 큐인지 로그로 가린다.
 * 설정 레코드는 소비기를 꺼 둔 환경에서도 바인딩해 검증한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobDeadLetterConsumerProperties.class)
class JobDeadLetterConsumerConfig {

    static final String REGISTER_DLQ = "preorder-register-dlq";
    static final String CANCEL_DLQ = "preorder-cancel-dlq";

    @Bean
    @ConditionalOnProperty(prefix = "nova.sqs.job-dead-letter", name = "enabled", havingValue = "true")
    JobDeadLetterConsumer registerDeadLetterConsumer(SqsClient sqs, SqsQueueUrls queueUrls,
                                                     JobDeadLetterConsumerProperties properties,
                                                     PreorderEventHandler handler, JsonMapper json) {
        return new JobDeadLetterConsumer(sqs, queueUrls, properties, REGISTER_DLQ, handler, json);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.sqs.job-dead-letter", name = "enabled", havingValue = "true")
    JobDeadLetterConsumer cancelDeadLetterConsumer(SqsClient sqs, SqsQueueUrls queueUrls,
                                                   JobDeadLetterConsumerProperties properties,
                                                   PreorderEventHandler handler, JsonMapper json) {
        return new JobDeadLetterConsumer(sqs, queueUrls, properties, CANCEL_DLQ, handler, json);
    }
}
