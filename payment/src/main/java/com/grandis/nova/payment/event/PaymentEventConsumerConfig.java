package com.grandis.nova.payment.event;

import com.grandis.nova.common.sqs.DelayedResend;
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
import java.util.Map;

/**
 * payment-events 큐를 받아 분배기에 넘긴다(nova.sqs.consumer.enabled=true 일 때). 처리하면 지우고, 실패하면 늦춰 다시 받다가
 * 재수신 한도를 넘으면 큐의 재드라이브 정책이 DLQ 로 옮긴다. 받기 · 지우기 · 다시 보이기는 common:sqs 가 맡는다.
 * 분배기가 미루라고 하면(승인 중 알림이 이르다) 같은 큐에 그만큼 지연을 두고 다시 보낸 뒤 원본을 지운다 — 받은 횟수를 쓰지 않는다.
 * 끄면 환불 요청 · 승인 중 알림이 큐에 쌓이기만 한다 — 운영에서 끄지 않는다.
 * DLQ 에서 되돌린 메시지는 되돌리기 표식(deadLetterId) 속성을 함께 받아, 분배기가 미루지 않고 예외 없이 끝내면 그 DLQ 행을 SUCCEEDED 로
 * 남긴다 — 메시지를 처리했다는 뜻이지 환불을 열었다는 뜻은 아니다(이미 환불 · 확정 실패면 열지 않고 끝난다). 미루면 표식을 사본에 이어
 * 보내 사본을 처리할 때 남긴다. 남기지 못하면 던진다 — 지우지 않고 다시 받는다(처리기는 같은 메시지를 두 번 받아도 된다).
 *
 * 설정 레코드 둘은 소비기를 꺼 둔 환경에서도 바인딩해 검증한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PaymentEventConsumerProperties.class, DeadLetterConsumerProperties.class})
class PaymentEventConsumerConfig {

    /** 받아 와서 지연 재전송 사본에 이어 실을 속성 — 보내는 쪽 아웃박스가 싣는 것과 되돌리기 표식. 받지 않은 속성은 사본에 실리지 않는다. */
    static final List<String> RESENT_ATTRIBUTES = List.of("eventType", "eventId", DeadLetterRedriver.DEAD_LETTER_ID_ATTRIBUTE);

    @Bean
    @ConditionalOnProperty(prefix = "nova.sqs.consumer", name = "enabled", havingValue = "true")
    RetryingQueueConsumer paymentEventConsumer(SqsClient sqs, SqsQueueUrls queueUrls, PaymentEventDispatcher dispatcher,
                                               DeadLetters deadLetters, PaymentEventConsumerProperties properties) {
        DelayedResend resend = new DelayedResend(sqs, queueUrls, properties.queue());
        return new RetryingQueueConsumer(sqs, queueUrls, properties.toSettings(), RESENT_ATTRIBUTES, message ->
                dispatcher.dispatch(message.body()).ifPresentOrElse(
                        delay -> resend.resend(message, Map.of(), delay),
                        () -> DeadLetterIds.of(message).ifPresent(deadLetters::markRedriveSucceeded)));
    }
}
