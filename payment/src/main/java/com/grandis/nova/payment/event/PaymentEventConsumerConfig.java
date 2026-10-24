package com.grandis.nova.payment.event;

import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.payment.deadletter.DeadLetterRedriver;
import com.grandis.nova.payment.deadletter.DeadLetters;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.List;

/**
 * payment-events 큐를 받아 분배기에 넘긴다(nova.sqs.consumer.enabled=true 일 때). 처리하면 지우고, 실패하면 늦춰 다시 받다가
 * 재수신 한도를 넘으면 큐의 재드라이브 정책이 DLQ 로 옮긴다. 받기 · 지우기 · 다시 보이기는 common:sqs 가 맡는다.
 * 끄면 환불 요청이 큐에 쌓이기만 한다 — 운영에서 끄지 않는다.
 * DLQ 에서 되돌린 메시지는 되돌리기 표식(deadLetterId) 속성을 함께 받아, 분배기가 예외 없이 끝내면 그 DLQ 행을 SUCCEEDED 로 남긴다 —
 * 메시지를 처리했다는 뜻이지 환불을 열었다는 뜻은 아니다(이미 환불 · 확정 실패면 열지 않고 끝난다). 남기지 못하면 던진다 —
 * 지우지 않고 다시 받는다(처리기는 같은 메시지를 두 번 받아도 된다).
 *
 * 설정 레코드 둘은 소비기를 꺼 둔 환경에서도 바인딩해 검증한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PaymentEventConsumerProperties.class, DeadLetterConsumerProperties.class})
class PaymentEventConsumerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "nova.sqs.consumer", name = "enabled", havingValue = "true")
    RetryingQueueConsumer paymentEventConsumer(SqsClient sqs, SqsQueueUrls queueUrls, PaymentEventDispatcher dispatcher,
                                               DeadLetters deadLetters, PaymentEventConsumerProperties properties) {
        return new RetryingQueueConsumer(sqs, queueUrls, properties.toSettings(),
                List.of(DeadLetterRedriver.DEAD_LETTER_ID_ATTRIBUTE), message -> {
                    dispatcher.dispatch(message.body());
                    DeadLetterIds.of(message).ifPresent(deadLetters::markRedriveSucceeded);
                });
    }
}
