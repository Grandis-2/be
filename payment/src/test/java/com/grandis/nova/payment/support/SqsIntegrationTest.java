package com.grandis.nova.payment.support;

import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link PaymentIntegrationTest} 에 실제 SQS 프로토콜(Floci)을 더한 통합 테스트. 소비기를 켠다
 * (application-sqs-test.properties). 큐는 TestQueues 로 넣고 꺼내 본다.
 *
 * 아웃박스 전송을 SQS 로 바꾼다(transport=sqs). {@link PaymentIntegrationTest} 의 인라인 transport=log 를 이겨야 하고
 * 전송 구현은 조건부 빈이라 컨텍스트를 올리기 전에 정해져야 한다 — 프로필 파일은 인라인 값에 진다.
 * Floci 컨테이너 · 큐 도우미는 common:sqs 의 테스트 픽스처다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@PaymentIntegrationTest
@ActiveProfiles("sqs-test")
@TestPropertySource(properties = "nova.outbox.transport=sqs")
@Import(SqsTestConfig.class)
public @interface SqsIntegrationTest {
}
