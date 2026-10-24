package com.grandis.nova.payment.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 큐 연동이 없는 통합 테스트의 DLQ 되돌리기 대역. 모든 {@link PaymentIntegrationTest} 가 같이 써 컨텍스트를 늘리지 않는다.
 * 큐 연동(nova.sqs.region)이 있는 {@link SqsIntegrationTest} 는 진짜 큐로 보내므로 두지 않는다 — 둘이면 어느 쪽인지 모호하다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class DeadLetterTestConfig {

    @Bean
    @ConditionalOnExpression("'${nova.sqs.region:}'.isEmpty()")
    RecordingDeadLetterRedriver recordingDeadLetterRedriver() {
        return new RecordingDeadLetterRedriver();
    }
}
